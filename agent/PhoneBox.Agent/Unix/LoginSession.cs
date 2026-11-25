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

/// <summary><see cref="ILoginSession"/> backed by <c>loginctl</c>; GNOME listens for the Unlock signal it sends.</summary>
public sealed class LoginSession : ILoginSession
{
    public string? GraphicalSessionId()
    {
        var id = Sh.Run("loginctl", "show-user", Environment.UserName, "-p", "Display", "--value")?.Trim();
        return string.IsNullOrEmpty(id) ? null : id;
    }

    public bool IsLocked(string id) =>
        Sh.Run("loginctl", "show-session", id, "-p", "LockedHint", "--value")?.Trim() == "yes";

    public bool Unlock(string id) => Sh.Run("loginctl", "unlock-session", id) is not null;
}
