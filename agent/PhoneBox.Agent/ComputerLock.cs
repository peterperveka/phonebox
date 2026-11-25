namespace PhoneBox.Agent;

/// <summary>
/// Whether the computer's session is locked, for "Follow the computer lock": macOS through the window server, Linux
/// through logind (the same session the fingerprint unlock watches). null means the lock cannot be read right now.
/// </summary>
internal static class ComputerLock
{
    public static bool Supported => OperatingSystem.IsMacOS() || OperatingSystem.IsLinux();

    public static bool? Read(ILoginSession? login)
    {
        if (OperatingSystem.IsMacOS()) return MacSessionLock.IsLocked();
        if (!OperatingSystem.IsLinux() || login is null) return null;
        return login.GraphicalSessionId() is { } id ? login.IsLocked(id) : null;
    }
}
