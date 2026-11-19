using System;
using System.Collections;
using System.Collections.Generic;
using System.Linq;
using System.Linq.Expressions;
using System.Threading;
using Mpp.Client.Internal;
using Mpp.Client.Internal.Linq;

namespace Mpp.Client.Linq;

public sealed class MppQueryable<T> : IOrderedQueryable<T>, IAsyncEnumerable<T>, IMppQueryable
{
    private readonly MppQueryProvider _provider;

    internal MppQueryable(MppQueryProvider provider, Expression? expression)
    {
        _provider = provider;
        Expression = expression ?? Expression.Constant(this);
    }

    public Type ElementType => typeof(T);

    public Expression Expression { get; }

    public IQueryProvider Provider => _provider;

    MppQueryProvider IMppQueryable.Provider => _provider;

    bool IMppQueryable.IsRoot => Expression is ConstantExpression constant && ReferenceEquals(constant.Value, this);

    public IEnumerator<T> GetEnumerator() => _provider.Execute<IEnumerable<T>>(Expression).GetEnumerator();

    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();

    public async IAsyncEnumerator<T> GetAsyncEnumerator(CancellationToken cancellationToken = default)
    {
        var rows = await _provider.ExecuteAsync<IEnumerable<T>>(Expression, cancellationToken).ConfigureAwait(false);
        foreach (var row in rows)
        {
            yield return row;
        }
    }

    public override string ToString() =>
        ((IMppQueryable)this).IsRoot ? $"{_provider.Root.Project}.{_provider.Root.Collection}" : Expression.ToString();

    internal static MppQueryable<T> Over(IQueryTarget<T> target) => new(new MppQueryProvider(new QueryRoot<T>(target)), null);
}
