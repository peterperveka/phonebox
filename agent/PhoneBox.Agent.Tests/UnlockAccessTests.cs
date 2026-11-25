using Microsoft.AspNetCore.Http;
using PhoneBox.Agent;
using Xunit;

/// <summary>Who may call which unlock endpoint: the phone reaches an allow-list, the helper's endpoints stay on loopback.</summary>
public sealed class UnlockAccessTests
{
    [Theory]
    [InlineData("/api/unlock/state")]
    [InlineData("/api/unlock/enroll")]
    [InlineData("/api/unlock/answer")]
    [InlineData("/api/unlock/screen")]
    public void PhonePathsAreAllowed(string p) => Assert.True(Lan.AllowedOverLan(new PathString(p)));

    [Theory]
    [InlineData("/api/unlock/request")]
    [InlineData("/api/unlock/key")]
    [InlineData("/api/unlock")]
    [InlineData("/api/unlock/state/x")]
    public void HelperPathsStayLocal(string p) => Assert.False(Lan.AllowedOverLan(new PathString(p)));
}
