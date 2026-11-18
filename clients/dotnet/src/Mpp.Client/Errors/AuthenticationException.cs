using System;
using Mpp.Client.Protocol;

namespace Mpp.Client.Errors;

public sealed class AuthenticationException : MppException
{
    public AuthenticationException()
    {
    }

    public AuthenticationException(string message)
        : base(message)
    {
    }

    public AuthenticationException(string message, Exception? innerException)
        : base(message, innerException)
    {
    }

    public AuthenticationException(string message, string? rawCode, Status? status, string? operation, Exception? innerException = null)
        : base(message, rawCode, status, operation, innerException)
    {
    }
}
