using System;
using Mpp.Client.Protocol;

namespace Mpp.Client.Errors;

public sealed class UnknownOutcomeException : MppException
{
    public UnknownOutcomeException()
    {
    }

    public UnknownOutcomeException(string message)
        : base(message)
    {
    }

    public UnknownOutcomeException(string message, Exception? innerException)
        : base(message, innerException)
    {
    }

    public UnknownOutcomeException(string message, string? rawCode, Status? status, string? operation, Exception? innerException = null)
        : base(message, rawCode, status, operation, innerException)
    {
    }
}
