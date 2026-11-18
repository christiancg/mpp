using System;
using Mpp.Client.Protocol;

namespace Mpp.Client.Errors;

public class ConnectionLostException : MppException
{
    public ConnectionLostException()
    {
    }

    public ConnectionLostException(string message)
        : base(message)
    {
    }

    public ConnectionLostException(string message, Exception? innerException)
        : base(message, innerException)
    {
    }

    public ConnectionLostException(string message, string? rawCode, Status? status, string? operation, Exception? innerException = null)
        : base(message, rawCode, status, operation, innerException)
    {
    }
}
