package com.mulesoft.connectors.grpc;

import static com.mulesoft.connectors.grpc.ExtensionClassName.MEDIA_TYPE_ANNOTATION;
import static com.mulesoft.connectors.grpc.ExtensionClassName.OPTIONAL_ANNOTATION;

import com.squareup.javapoet.AnnotationSpec;

public class BuilderUtils {

    private BuilderUtils() {

    }

    public static  AnnotationSpec mediaTypeOf(String mediaType) {
        return AnnotationSpec
                .builder(MEDIA_TYPE_ANNOTATION)
                .addMember("value", "$S", mediaType)
                .build();
    }

    public static AnnotationSpec defaultValue(String defaultValue) {
        return AnnotationSpec
                .builder(OPTIONAL_ANNOTATION)
                .addMember("defaultValue", "$S", defaultValue)
                .build();
    }
}