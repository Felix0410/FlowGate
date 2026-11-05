package com.flowgate.control.security;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.flowgate.control.entity.Principal;
import com.flowgate.control.mapper.PrincipalMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 仅首次按环境变量初始化管理员；既有账号绝不被启动参数覆盖。 */
@Component
public class BootstrapAdmin implements ApplicationRunner {
    private final PrincipalMapper mapper;
    private final Environment env;
    public BootstrapAdmin(PrincipalMapper mapper, Environment env) { this.mapper = mapper; this.env = env; }
    @Override public void run(ApplicationArguments args) {
        String username = env.getProperty("FG_BOOTSTRAP_ADMIN_USERNAME");
        String hash = env.getProperty("FG_BOOTSTRAP_ADMIN_BCRYPT");
        if (username == null && hash == null) return;
        com.flowgate.contract.Rules.text(username, "管理员名");
        com.flowgate.contract.Rules.require(hash != null && hash.matches("\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}"), "需要有效 BCrypt 哈希");
        if (mapper.selectCount(new QueryWrapper<Principal>().eq("principal_type", "ADMIN").eq("role", "ADMIN")) != 0) return;
        Principal principal = new Principal(); principal.username = username; principal.passwordHash = hash;
        principal.principalType = "ADMIN"; principal.role = "ADMIN"; principal.scopesJson = "[]"; principal.enabled = true;
        mapper.insert(principal);
    }
}
