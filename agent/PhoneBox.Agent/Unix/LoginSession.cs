namespace PhoneBox.Agent;

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
