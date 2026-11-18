using System;
using Mpp.Client.Protocol;

namespace Mpp.Client.Errors;

public sealed class UnavailableException : MppException
{
    public UnavailableException()
    {
    }

    public UnavailableException(string message)
        : base(message)
    {
    }

    public UnavailableException(string message, Exception? innerException)
        : base(message, innerException)
    {
    }

    public UnavailableException(string message, string? rawCode, Status? status, string? operation, Exception? innerException = null)
        : base(message, rawCode, status, operation, innerException)
    {
    }
}
