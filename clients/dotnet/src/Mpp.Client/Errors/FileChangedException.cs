using System;
using Mpp.Client.Protocol;

namespace Mpp.Client.Errors;

public sealed class FileChangedException : MppException
{
    public FileChangedException()
    {
    }

    public FileChangedException(string message)
        : base(message)
    {
    }

    public FileChangedException(string message, Exception? innerException)
        : base(message, innerException)
    {
    }

    public FileChangedException(string message, string? rawCode, Status? status, string? operation, Exception? innerException = null)
        : base(message, rawCode, status, operation, innerException)
    {
    }
}
