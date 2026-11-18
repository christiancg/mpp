using System.Diagnostics.CodeAnalysis;

namespace Mpp.Client.Query;

[SuppressMessage("Naming", "CA1720")]
public enum CastType
{
    Number,
    String,
    Boolean,
    JsonCustom,
}
