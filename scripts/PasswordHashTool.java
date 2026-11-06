import java.io.BufferedReader;
import java.io.InputStreamReader;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** 本地一次性 BCrypt 工具：交互时隐藏输入；管道时读标准输入，不接受命令行密码。 */
class PasswordHashTool {
    public static void main(String[] args) throws Exception {
        var console = System.console();
        String password = console == null ? new BufferedReader(new InputStreamReader(System.in)).readLine()
                : new String(console.readPassword("管理员密码（不回显）: "));
        if (password == null || password.isBlank()) throw new IllegalArgumentException("密码不能为空");
        System.out.println(new BCryptPasswordEncoder().encode(password));
    }
}
