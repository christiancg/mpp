using System;
using System.Linq.Expressions;
using System.Threading;
using System.Threading.Tasks;
using Mpp.Client.Linq;

namespace Mpp.Client.Internal.Linq;

internal static class QueryExecutor
{
    public static QueryModel Translate(MppQueryProvider provider, Expression expression) =>
        new QueryModelBuilder(provider).Build(PartialEvaluator.Evaluate(expression));

    public static async Task<object?> ExecuteAsync(MppQueryProvider provider, Expression expression, Type resultType, CancellationToken ct)
    {
        var model = Translate(provider, expression);
        var rows = model.SendsNothing
            ? []
            : await provider.Root.AggregateAsync(model.Steps, model.DirtyRead, ct).ConfigureAwait(false);
        return ResultShaper.Shape(model, rows, resultType, provider.Root.Context.Json);
    }
}
