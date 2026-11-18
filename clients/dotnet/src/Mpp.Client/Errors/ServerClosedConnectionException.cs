using System;
using Mpp.Client.Protocol;

namespace Mpp.Client.Errors;

public sealed class ServerClosedConnectionException : MppException
{
    public ServerClosedConnectionException()
    {
    }

    public ServerClosedConnectionException(string message)
        : base(message)
    {
    }

    public ServerClosedConnectionException(string message, Exception? innerException)
        : base(message, innerException)
    {
    }

    public ServerClosedConnectionException(string message, string? rawCode, Status? status, string? operation, Exception? innerException = null)
        : base(message, rawCode, status, operation, innerException)
    {
    }
}
