using System.Runtime.InteropServices;

namespace PhoneBox.Agent;

/// <summary>Follows the computer's session lock (macOS console session, Linux logind) while the option is enabled.</summary>
public sealed class SessionLockMonitor(ConfigStore cfg, AdbReverse adb, ILogger<SessionLockMonitor> log) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken ct)
    {
        var warned = false;
        string? lastFailure = null;
        while (!ct.IsCancellationRequested)
        {
            try
            {
                if (cfg.Get().Display.FollowComputerLock && ComputerLock.Supported)
                {
                    var available = await adb.SyncSessionAsync(ct);
                    if (!available && !warned)
                    {
                        log.LogWarning("Lock sync is unavailable; start the agent in the desktop session");
                    }
                    warned = !available;
                }
                else
                {
                    await adb.ResetSessionAsync(ct);
                    warned = false;
                }
                lastFailure = null;
                await Task.Delay(TimeSpan.FromSeconds(1), ct);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { return; }
            catch (Exception ex)
            {
                // the same failure every 5 s (e.g. a phone that refuses injected keys) is logged once, not 720 times an hour
                var what = ex.Message;
                if (what != lastFailure) log.LogWarning(ex, "Could not follow the computer lock (a phone that refuses key presses over USB needs \"USB debugging (Security settings)\" on Xiaomi)");
                lastFailure = what;
                await Task.Delay(TimeSpan.FromSeconds(5), ct);
            }
        }
    }
}

/// <summary>Reads the caller's window-server session. An absent lock key means unlocked.</summary>
internal static class MacSessionLock
{
    public static bool? IsLocked()
    {
        if (!OperatingSystem.IsMacOS()) return null;
        var session = CGSessionCopyCurrentDictionary();
        if (session == IntPtr.Zero) return null;
        try
        {
            var onConsole = GetBoolean(session, "kCGSSessionOnConsoleKey");
            var locked = GetBoolean(session, "CGSSessionScreenIsLocked");
            return onConsole == false || locked == true;
        }
        finally { CFRelease(session); }
    }

    private static bool? GetBoolean(IntPtr dictionary, string name)
    {
        var key = CFStringCreateWithCString(IntPtr.Zero, name, 0x08000100); // UTF-8
        if (key == IntPtr.Zero) return null;
        try
        {
            var value = CFDictionaryGetValue(dictionary, key);
            return value == IntPtr.Zero || CFGetTypeID(value) != CFBooleanGetTypeID()
                ? null : CFBooleanGetValue(value) != 0;
        }
        finally { CFRelease(key); }
    }

    [DllImport("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics")]
    private static extern IntPtr CGSessionCopyCurrentDictionary();
    [DllImport("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", CharSet = CharSet.Ansi)]
    private static extern IntPtr CFStringCreateWithCString(IntPtr allocator, string text, uint encoding);
    [DllImport("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation")]
    private static extern IntPtr CFDictionaryGetValue(IntPtr dictionary, IntPtr key);
    [DllImport("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation")]
    private static extern nint CFGetTypeID(IntPtr value);
    [DllImport("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation")]
    private static extern nint CFBooleanGetTypeID();
    [DllImport("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation")]
    private static extern byte CFBooleanGetValue(IntPtr value);
    [DllImport("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation")]
    private static extern void CFRelease(IntPtr value);
}
