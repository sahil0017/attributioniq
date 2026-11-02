package com.attributioniq.attribution_iq.api;

import com.attributioniq.attribution_iq.domain.AttributionModel;
import org.jspecify.annotations.Nullable;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class AttributionModelConverter implements Converter<String, AttributionModel> {
    @Override
    public AttributionModel convert(@Nullable String source) {
        return AttributionModel.fromValue(source);
    }
}
