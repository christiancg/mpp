using System.Text.Json.Nodes;

namespace Mpp.Client.Internal;

internal enum Route
{
    Response,
    Push,
    ServerClose,
}

internal static class FrameRouter
{
    public const string ListenEndedCode = "410-1";

    public static Route Of(JsonObject frame, bool awaitingClose)
    {
        return ResponseDecoder.TextOf(frame["type"]) switch
        {
            "LISTEN" when IsPush(frame) => Route.Push,
            "CLOSE_CONNECTION" when !awaitingClose => Route.ServerClose,
            _ => Route.Response,
        };
    }

    public static bool IsListenEnd(JsonObject frame) => ResponseDecoder.TextOf(frame["errorCode"]) == ListenEndedCode;

    private static bool IsPush(JsonObject frame) =>
        ResponseDecoder.BooleanOf(frame["isUpdate"], false) || IsListenEnd(frame);
}
