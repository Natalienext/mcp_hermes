package org.example.wardrobe.web;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;

/**
 * Конвертирует значения фильтров ("SKIRT") в enum.
 * На неизвестное значение бросает ошибку со списком допустимых — агент сможет исправить вызов.
 */
class EnumParamConverterFactory implements ConverterFactory<String, Enum<?>> {

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends Enum<?>> Converter<String, T> getConverter(Class<T> targetType) {
        return source -> {
            String value = source.trim();
            if (value.isEmpty()) {
                return null;
            }
            try {
                return (T) Enum.valueOf((Class) targetType, value);
            } catch (IllegalArgumentException e) {
                String allowed = Arrays.stream(targetType.getEnumConstants())
                        .map(Enum::name)
                        .collect(Collectors.joining(", "));
                throw new IllegalArgumentException(
                        "Unknown value '" + value + "'. Allowed values: " + allowed);
            }
        };
    }
}
