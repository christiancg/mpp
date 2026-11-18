using System.Collections.Generic;

namespace Mpp.Client;

public sealed record ListenUpdate<T>(string ListenId, IReadOnlyList<T> Results, string ResultHash, bool Initial);
