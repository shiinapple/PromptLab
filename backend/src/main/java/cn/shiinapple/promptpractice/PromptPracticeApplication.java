package cn.shiinapple.promptpractice;

import java.util.Arrays;

import cn.shiinapple.promptpractice.ops.Operations;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PromptPracticeApplication {
    public static void main(String[] args) {
        if (args.length > 0 && "--ops".equals(args[0])) {
            if (args.length > 1 && "restore".equals(args[1])) {
                int code = Operations.restore(Arrays.copyOfRange(args, 1, args.length));
                if (code != 0) System.exit(code);
                return;
            }
            int code;
            try (var context = new SpringApplicationBuilder(PromptPracticeApplication.class)
                    .web(WebApplicationType.NONE).run()) {
                code = context.getBean(Operations.class).execute(Arrays.copyOfRange(args, 1, args.length));
            }
            if (code != 0) System.exit(code);
            return;
        }
        SpringApplication.run(PromptPracticeApplication.class, args);
    }
}
