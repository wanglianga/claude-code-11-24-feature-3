package cc.royalty.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI royaltyOpenApi() {
        return new OpenAPI().info(new Info()
                .title("音乐版权使用归属与版税结算服务 API")
                .version("1.0.0")
                .description("平台使用明细归属、按使用日合同结算、有理数份额与最大余数法分摊、模拟付款回执，"
                        + "以及争议版税托管与裁决执行（范围求交冻结、并集去重、裁决预览/执行/撤回、托管付款绑定、"
                        + "追回事项与托管守恒台账）。金额一律为最小货币单位整数。Swagger UI: /swagger-ui.html")
                .contact(new Contact().name("royalty-settlement")));
    }
}
