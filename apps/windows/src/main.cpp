// TimeTrace Windows foreground application collector (console MVP).
// Logs app executable names, not window titles, URLs, screenshots or keystrokes.
// Windows 10/11, C++20, Win32 API. Zero third-party dependencies.
#include <windows.h>
#include <objbase.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace fs = std::filesystem;
using Clock = std::chrono::system_clock;

static std::atomic_bool running{true};
static constexpr std::uint64_t kIdleThresholdMs = 120'000;
static constexpr std::int64_t kCheckpointMs = 30'000;

static std::int64_t utc_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        Clock::now().time_since_epoch()).count();
}

static std::string to_utf8(const std::wstring& src) {
    if (src.empty()) return {};
    int n = WideCharToMultiByte(CP_UTF8, 0, src.data(), static_cast<int>(src.size()),
                                nullptr, 0, nullptr, nullptr);
    if (n <= 0) return {};
    std::string out(static_cast<std::size_t>(n), '\0');
    WideCharToMultiByte(CP_UTF8, 0, src.data(), static_cast<int>(src.size()),
                        out.data(), n, nullptr, nullptr);
    return out;
}

static std::string json_escape(const std::string& input) {
    static constexpr char hex[] = "0123456789abcdef";
    std::string out;
    out.reserve(input.size() + 12);
    for (unsigned char c : input) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    out += "\\u00";
                    out += hex[c >> 4];
                    out += hex[c & 0x0f];
                } else {
                    out += static_cast<char>(c);
                }
        }
    }
    return out;
}

static std::string make_guid() {
    GUID guid{};
    if (FAILED(CoCreateGuid(&guid))) return {};
    wchar_t text[40]{};
    StringFromGUID2(guid, text, 40);
    std::wstring value(text);
    // strip surrounding braces
    if (value.size() > 2) value = value.substr(1, value.size() - 2);
    return to_utf8(value);
}

static fs::path data_dir() {
    DWORD length = GetEnvironmentVariableW(L"LOCALAPPDATA", nullptr, 0);
    if (!length) throw std::runtime_error("LOCALAPPDATA is unavailable");
    std::vector<wchar_t> buffer(length);
    if (!GetEnvironmentVariableW(L"LOCALAPPDATA", buffer.data(), length)) {
        throw std::runtime_error("Cannot locate LOCALAPPDATA");
    }
    fs::path p(buffer.data());
    p /= L"TimeTrace";
    fs::create_directories(p);
    return p;
}

static std::string get_device_id(const fs::path& folder) {
    const auto file = folder / L"device_id.txt";
    {
        std::ifstream stream(file);
        std::string id;
        if (std::getline(stream, id) && !id.empty()) return id;
    }
    const std::string id = make_guid();
    if (id.empty()) throw std::runtime_error("Could not create device ID");
    std::ofstream stream(file, std::ios::binary | std::ios::trunc);
    if (!stream) throw std::runtime_error("Cannot write device ID");
    stream << id << '\n';
    stream.flush();
    if (!stream) throw std::runtime_error("Failed to persist device ID");
    return id;
}

// 32-bit uptime ticks are intentionally subtracted as DWORD (wrap-safe for short intervals).
static bool user_is_idle() {
    LASTINPUTINFO info{};
    info.cbSize = sizeof(info);
    if (!GetLastInputInfo(&info)) return false;
    const DWORD delta = static_cast<DWORD>(GetTickCount() - info.dwTime);
    return static_cast<std::uint64_t>(delta) >= kIdleThresholdMs;
}

static std::string active_executable() {
    if (user_is_idle()) return {};
    HWND hwnd = GetForegroundWindow();
    if (!hwnd) return {};
    DWORD pid = 0;
    GetWindowThreadProcessId(hwnd, &pid);
    if (!pid) return {};

    HANDLE h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    if (!h) return {}; // unknown/privileged apps are not invented
    std::vector<wchar_t> buffer(32768);
    DWORD size = static_cast<DWORD>(buffer.size());
    BOOL ok = QueryFullProcessImageNameW(h, 0, buffer.data(), &size);
    CloseHandle(h);
    if (!ok || !size) return {};
    fs::path exe(std::wstring(buffer.data(), size));
    return "windows:" + to_utf8(exe.filename().wstring());
}

static void append_segment(const fs::path& filename, const std::string& device,
                           const std::string& app, std::int64_t start,
                           std::int64_t end) {
    if (app.empty() || end <= start) return;
    const std::string id = make_guid();
    if (id.empty()) throw std::runtime_error("Cannot generate segment ID");
    std::ofstream out(filename, std::ios::binary | std::ios::app);
    if (!out) throw std::runtime_error("Cannot open event log");
    out << "{\"segment_id\":\"" << json_escape(id)
        << "\",\"device_id\":\"" << json_escape(device)
        << "\",\"app_id\":\"" << json_escape(app)
        << "\",\"start_ms\":" << start
        << ",\"end_ms\":" << end
        << ",\"source\":\"windows_foreground_poll\""
        << ",\"confidence\":\"observed\"}\n";
    out.flush();
    if (!out) throw std::runtime_error("Cannot persist event segment");
}

static BOOL WINAPI console_handler(DWORD signal) {
    switch (signal) {
        case CTRL_C_EVENT:
        case CTRL_BREAK_EVENT:
            running.store(false);
            return TRUE;
        default: return FALSE;
    }
}

int main() {
    HANDLE mutex = CreateMutexW(nullptr, FALSE, L"Local\\TimeTraceCollector");
    if (!mutex) {
        std::cerr << "Cannot create collector mutex.\n";
        return 1;
    }
    if (GetLastError() == ERROR_ALREADY_EXISTS) {
        std::cerr << "Collector already running in this session.\n";
        CloseHandle(mutex);
        return 1;
    }
    try {
        SetConsoleCtrlHandler(console_handler, TRUE);
        const fs::path folder = data_dir();
        const fs::path output = folder / L"segments.jsonl";
        const std::string device = get_device_id(folder);
        std::cout << "TimeTrace Windows collector (MVP)\n"
                  << "Output: " << to_utf8(output.wstring()) << "\n"
                  << "Device ID: " << device << "\n"
                  << "Press Ctrl+C to stop. No window titles or key presses are recorded.\n";
        std::string current;
        std::int64_t since = 0;
        while (running.load()) {
            const std::int64_t now = utc_ms();
            const std::string next = active_executable();
            if (next != current) {
                append_segment(output, device, current, since, now);
                current = next;
                since = now;
                if (!current.empty()) std::cout << "Active: " << current << '\n';
                else std::cout << "Idle / unknown\n";
            } else if (!current.empty() && now - since >= kCheckpointMs) {
                // Durable 30-second slices; avoids losing an entire long session on crash.
                append_segment(output, device, current, since, now);
                since = now;
            }
            std::this_thread::sleep_for(std::chrono::seconds(1));
        }
        append_segment(output, device, current, since, utc_ms());
        std::cout << "Stopped.\n";
    } catch (const std::exception& e) {
        std::cerr << "TimeTrace error: " << e.what() << '\n';
        CloseHandle(mutex);
        return 1;
    }
    CloseHandle(mutex);
    return 0;
}
