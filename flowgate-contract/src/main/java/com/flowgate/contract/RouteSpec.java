package com.flowgate.contract;

/** 发布后的单条路由及其鉴权要求；同一不可变记录绑定版本，避免跨版取权限。 */
public record RouteSpec(String routeId, String method, String publicPath, String upstreamPath,
                        String upstreamUri, boolean isPublic, String requiredScope,
                        int priority, long configVersion) {}
