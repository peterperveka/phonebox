namespace PhoneBox.Agent;

/// <summary>The graphical login session of this user, as systemd-logind sees it.</summary>
public interface ILoginSession
{
    /// <summary>Id of the user's graphical session, or null if there is none.</summary>
    string? GraphicalSessionId();

    /// <summary>True while the desktop has locked the session (logind <c>LockedHint</c>).</summary>
    bool IsLocked(string id);

    /// <summary>Asks the desktop to unlock the session; true if the request was sent.</summary>
    bool Unlock(string id);
}

/// <summary>Where there is no logind (Windows): no graphical session, so the lock features stay off.</summary>
public sealed class NoLoginSession : ILoginSession
{
    public string? GraphicalSessionId() => null;
    public bool IsLocked(string id) => false;
    public bool Unlock(string id) => false;
}
