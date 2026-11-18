using System;
using Mpp.Client.Protocol;

namespace Mpp.Client.Errors;

public sealed class RequestTimeoutException : ConnectionLostException
{
    public RequestTimeoutException()
    {
    }

    public RequestTimeoutException(string message)
        : base(message)
    {
    }

    public RequestTimeoutException(string message, Exception? innerException)
        : base(message, innerException)
    {
    }

    public RequestTimeoutException(string message, string? rawCode, Status? status, string? operation, Exception? innerException = null)
        : base(message, rawCode, status, operation, innerException)
    {
    }
}
