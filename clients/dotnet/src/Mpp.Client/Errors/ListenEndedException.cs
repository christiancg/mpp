using System;
using Mpp.Client.Protocol;

namespace Mpp.Client.Errors;

public sealed class ListenEndedException : MppException
{
    public ListenEndedException()
    {
    }

    public ListenEndedException(string message)
        : base(message)
    {
    }

    public ListenEndedException(string message, Exception? innerException)
        : base(message, innerException)
    {
    }

    public ListenEndedException(string message, string? rawCode, Status? status, string? operation, Exception? innerException = null)
        : base(message, rawCode, status, operation, innerException)
    {
    }
}
