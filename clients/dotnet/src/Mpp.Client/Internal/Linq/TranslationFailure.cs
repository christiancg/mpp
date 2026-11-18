using System;
using System.Linq.Expressions;

namespace Mpp.Client.Internal.Linq;

internal static class TranslationFailure
{
    public static NotSupportedException For(Expression expression, string reason) =>
        new($"MPP cannot translate '{expression}': {reason}");
}
