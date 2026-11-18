using System;
using Mpp.Client.Protocol;

namespace Mpp.Client.Errors;

public sealed class PoolExhaustedException : MppException
{
    public PoolExhaustedException()
    {
    }

    public PoolExhaustedException(string message)
        : base(message)
    {
    }

    public PoolExhaustedException(string message, Exception? innerException)
        : base(message, innerException)
    {
    }

    public PoolExhaustedException(string message, string? rawCode, Status? status, string? operation, Exception? innerException = null)
        : base(message, rawCode, status, operation, innerException)
    {
    }
}
