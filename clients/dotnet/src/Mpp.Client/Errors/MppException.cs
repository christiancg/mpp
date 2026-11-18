using System;
using Mpp.Client.Protocol;

namespace Mpp.Client.Errors;

public class MppException : Exception
{
    public MppException()
    {
    }

    public MppException(string message)
        : base(message)
    {
    }

    public MppException(string message, Exception? innerException)
        : base(message, innerException)
    {
    }

    public MppException(string message, string? rawCode, Status? status, string? operation, Exception? innerException = null)
        : base(message, innerException)
    {
        RawCode = rawCode;
        Status = status;
        Operation = operation;
    }

    public string? RawCode { get; }

    public ErrorCode? Code => ErrorCode.Of(RawCode);

    public Status? Status { get; }

    public string? Operation { get; }

    public bool HasCode(string rawCode) => string.Equals(RawCode, rawCode, StringComparison.Ordinal);

    public bool HasCode(ErrorCode code)
    {
        ArgumentNullException.ThrowIfNull(code);
        return HasCode(code.Code);
    }
}
