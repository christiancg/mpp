namespace Mpp.Client.Files;

public sealed record FileWriteOptions
{
    public FileWriteMode Mode { get; init; } = FileWriteMode.Replace;

    public bool CreateParents { get; init; } = true;

    public string? IfVersion { get; init; }
}

public sealed record FileListOptions
{
    public bool Recursive { get; init; }

    public int? PageSize { get; init; }

    public string? After { get; init; }
}
