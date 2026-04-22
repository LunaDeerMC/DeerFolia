package cn.lunadeer.mc.deerfolia;

public final class ServerBuildInfoHelper {

    private ServerBuildInfoHelper() {
    }

    public static io.papermc.paper.ServerBuildInfo buildInfo() {
        return new io.papermc.paper.ServerBuildInfoImpl();
    }
}
