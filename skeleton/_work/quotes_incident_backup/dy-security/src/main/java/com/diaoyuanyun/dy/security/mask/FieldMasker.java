package com.diaoyuanyun.dy.security.mask;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.Module.SetupContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 字段裁剪器 (ADR-07): 无权限字段在<b>序列化前</b>从对象图整体移除, JSON 中<b>不出现该 key</b>。
 *
 * <p>实现方式: 注册 {@link SimpleModule}, 在 {@code setupModule} 阶段通过
 * {@link SetupContext#addBeanSerializerModifier} 注入 {@link BeanSerializerModifier}, 于
 * {@code changeProperties} 剔除名称属于 {@code forbiddenFieldNames} 的属性。绝不用置 null/空串/空数组
 * (那会泄露字段存在性, H-4)。
 *
 * <p>作用域按角色×字段组四档 (ADR-07), 由调用方传入当前角色被禁止下发的字段名集合。
 */
public final class FieldMasker {

    private FieldMasker() {
    }

    public static String mask(Object obj, Set<String> forbiddenFieldNames) {
        if (obj == null) {
            return "null";
        }
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new SimpleModule("dy-field-mask") {
            @Override
            public void setupModule(SetupContext context) {
                context.addBeanSerializerModifier(new BeanSerializerModifier() {
                    @Override
                    public List<BeanPropertyWriter> changeProperties(SerializationConfig config,
                                                                      BeanDescription beanDesc,
                                                                      List<BeanPropertyWriter> props) {
                        List<BeanPropertyWriter> kept = new ArrayList<>();
                        for (BeanPropertyWriter p : props) {
                            if (!forbiddenFieldNames.contains(p.getName())) {
                                kept.add(p);
                            }
                        }
                        return kept;
                    }
                });
            }
        });
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("字段裁剪序列化失败", e);
        }
    }
}
