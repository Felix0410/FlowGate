package com.flowgate.contract;
import java.util.List;
/** APP专用Redis会话，绝不写入旧CALLER命名空间或复用旧Session用途。 */
public record AppCredentialSession(long principalId,String principalType,List<String> scopes,long credentialGeneration) {}
