using PhoneBox.Agent;
using Xunit;

public sealed class ComputerLockTests
{
    private sealed class FakeSession(string? id, bool locked) : ILoginSession
    {
        public string? GraphicalSessionId() => id;
        public bool IsLocked(string sessionId) => locked;
        public bool Unlock(string sessionId) => true;
    }

    [Fact]
    public void LockedSessionIsLocked()
    {
        if (!OperatingSystem.IsLinux()) return;
        Assert.True(ComputerLock.Read(new FakeSession("7", locked: true)));
    }

    [Fact]
    public void UnlockedSessionIsUnlocked()
    {
        if (!OperatingSystem.IsLinux()) return;
        Assert.False(ComputerLock.Read(new FakeSession("7", locked: false)));
    }

    [Fact]
    public void NoSessionIsUnavailable()
    {
        if (!OperatingSystem.IsLinux()) return;
        Assert.Null(ComputerLock.Read(new FakeSession(null, locked: true)));
    }

    [Fact]
    public void NoLoginServiceIsUnavailable()
    {
        if (!OperatingSystem.IsLinux()) return;
        Assert.Null(ComputerLock.Read(null));
    }
}
