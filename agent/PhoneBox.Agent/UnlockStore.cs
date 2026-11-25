using System.Text.Json;

namespace PhoneBox.Agent;

/// <summary>The unlock the phone is asked to confirm: a fresh id and the challenge to sign.</summary>
public sealed record UnlockRequest(string Id, string Challenge);

/// <summary>
/// The one pending unlock request. The PAM helper posts a challenge and waits here; the phone answers with a signature,
/// or with null when the user pressed "Use password". Only one request is pending at a time: a newer one ends the older
/// one, which then gets no signature.
/// </summary>
public sealed class UnlockStore(PushStore events, ILogger<UnlockStore> log)
{
    private readonly Lock _gate = new();
    private (UnlockRequest Request, TaskCompletionSource<string?> Answer)? _current;

    /// <summary>The request the phone can answer right now, if any.</summary>
    public UnlockRequest? Pending()
    {
        lock (_gate) return _current?.Request;
    }

    /// <summary>Waits for the phone: the signature, or null (phone cancelled, timeout, connection closed, replaced).</summary>
    public async Task<string?> RequestAsync(string challenge, TimeSpan timeout, CancellationToken ct)
    {
        var request = new UnlockRequest(Convert.ToHexString(System.Security.Cryptography.RandomNumberGenerator.GetBytes(8)).ToLowerInvariant(), challenge);
        var tcs = new TaskCompletionSource<string?>(TaskCreationOptions.RunContinuationsAsynchronously);
        TaskCompletionSource<string?>? replaced;
        lock (_gate)
        {
            replaced = _current?.Answer;
            _current = (request, tcs);
        }
        replaced?.TrySetResult(null); // the older request ends without a signature
        events.Emit("unlock", JsonSerializer.SerializeToElement(new { id = request.Id, pending = true }));
        log.LogInformation("unlock {Id}: sent to the phone", request.Id);
        try
        {
            await Task.WhenAny(tcs.Task, Task.Delay(timeout, ct));
            // The answer task is the one outcome: a timeout or cancel closes it with null, so an Answer that loses
            // the race reports false; an Answer that won before this line is returned.
            tcs.TrySetResult(null);
            return await tcs.Task;
        }
        catch (OperationCanceledException) { tcs.TrySetResult(null); return null; }
        finally
        {
            lock (_gate)
            {
                if (_current is { } c && ReferenceEquals(c.Answer, tcs)) _current = null;
            }
            events.Emit("unlock", JsonSerializer.SerializeToElement(new { id = request.Id, pending = false }));
        }
    }

    /// <summary>The phone's answer; false if <paramref name="id"/> is not the pending request.</summary>
    public bool Answer(string id, string? signature)
    {
        TaskCompletionSource<string?> tcs;
        lock (_gate)
        {
            if (_current is not { } c || c.Request.Id != id) return false;
            tcs = c.Answer;
        }
        if (!tcs.TrySetResult(signature)) return false;
        log.LogInformation("unlock {Id}: {Outcome} from the phone", id, signature is null ? "cancelled" : "signed");
        return true;
    }
}
