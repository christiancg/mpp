using System;
using System.Collections.Generic;
using System.Linq;
using System.Reflection;
using Xunit;

namespace Mpp.Client.Tests.Structure;

public sealed class NoInternalLeakTest
{
    private const BindingFlags Visible = BindingFlags.Public | BindingFlags.Instance | BindingFlags.Static | BindingFlags.DeclaredOnly;

    [Fact]
    public void NoPublicSignatureNamesAnInternalType()
    {
        var leaks = typeof(MppClient).Assembly.GetExportedTypes()
            .SelectMany(type => SignatureTypes(type).Where(IsHidden).Select(hidden => $"{type.FullName} exposes {hidden.FullName}"))
            .Distinct()
            .ToList();
        Assert.True(leaks.Count == 0, string.Join('\n', leaks));
    }

    [Fact]
    public void NoInternalNamespaceTypeIsPublic()
    {
        var exposed = typeof(MppClient).Assembly.GetExportedTypes()
            .Where(type => type.Namespace?.StartsWith("Mpp.Client.Internal", StringComparison.Ordinal) == true)
            .Select(type => type.FullName)
            .ToList();
        Assert.Empty(exposed);
    }

    private static IEnumerable<Type> SignatureTypes(Type type)
    {
        IEnumerable<Type> types = [.. type.GetInterfaces().Where(IsVisibleInterface(type)), .. type.BaseType is null ? [] : new[] { type.BaseType }];
        var memberTypes = type.GetMembers(Visible).SelectMany(member => member switch
        {
            MethodBase method => method.GetParameters().Select(parameter => parameter.ParameterType)
                .Concat(method is MethodInfo info ? [info.ReturnType] : []),
            PropertyInfo property => [property.PropertyType],
            FieldInfo field => [field.FieldType],
            EventInfo e => e.EventHandlerType is null ? [] : [e.EventHandlerType],
            _ => [],
        });
        return types.Concat(memberTypes).SelectMany(Flatten);
    }

    private static Func<Type, bool> IsVisibleInterface(Type type) =>
        candidate => type.GetInterfaceMap(candidate).TargetMethods.Any(method => method.IsPublic);

    private static IEnumerable<Type> Flatten(Type type)
    {
        var element = type.HasElementType ? type.GetElementType()! : type;
        yield return element;
        if (!element.IsGenericType)
        {
            yield break;
        }
        foreach (var argument in element.GetGenericArguments().SelectMany(Flatten))
        {
            yield return argument;
        }
    }

    private static bool IsHidden(Type type) =>
        !type.IsGenericParameter && type.Assembly == typeof(MppClient).Assembly && !type.IsVisible;
}
