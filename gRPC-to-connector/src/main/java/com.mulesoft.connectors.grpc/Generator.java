package com.mulesoft.connectors.grpc;

import static com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label.LABEL_REPEATED;
import static com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.TYPE_BYTES;
import static com.mulesoft.connectors.grpc.ExtensionClassName.CURSOR_ITERATOR_PROVIDER;
import static com.mulesoft.connectors.grpc.ExtensionClassName.INJECT_ANNOTATION;
import static com.mulesoft.connectors.grpc.ExtensionClassName.LISTENABLE_FUTURE;
import static com.mulesoft.connectors.grpc.ExtensionClassName.MANAGED_CHANNEL;
import static com.mulesoft.connectors.grpc.ExtensionClassName.MANAGED_CHANNEL_BUILDER;
import static com.mulesoft.connectors.grpc.ExtensionClassName.RESULT;
import static com.mulesoft.connectors.grpc.ExtensionClassName.SCHEDULER;
import static com.mulesoft.connectors.grpc.ExtensionClassName.SCHEDULER_SERVICE;
import static com.mulesoft.connectors.grpc.ExtensionClassName.STREAMING_HELPER;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PUBLIC;

import com.mulesoft.connectors.grpc.ParameterBuilder.MethodParameterBuilder;
import com.mulesoft.connectors.grpc.extension.Descriptor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import javax.lang.model.element.Modifier;

import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeSpec.Builder;

public class Generator {

    public static final ClassName STREAM_OBSERVER = ClassName.get("io.grpc.stub", "StreamObserver");
    Map<String, TypeSpec> types = new HashMap<>();
    private String baseJavaPackage;
    private String apiPackage;

    public static void main(String [] args) throws IOException {
       new Generator().process(null, new File("/Users/ewasinger/Documents/mulesoft/experiments/gRPC-connect/target/generated-sources"), Thread.currentThread().getContextClassLoader().getResourceAsStream("descriptor.desc"));
    }

    public void process(Descriptor descriptor, File outputFolder, InputStream descriptorFile) throws IOException {
        DescriptorProtos.FileDescriptorSet descriptorSet = DescriptorProtos.FileDescriptorSet.parseFrom(descriptorFile);
        FileDescriptorProto file = descriptorSet.getFile(0);

        baseJavaPackage = file.getOptions().getJavaPackage();
        apiPackage = baseJavaPackage + ".api";

        for (DescriptorProto descriptorProto : file.getMessageTypeList()) {
            createMethod(descriptorProto, outputFolder);
        }

        ExtensionBuilder extensionBuilder = new ExtensionBuilder(descriptor.getName(), descriptor.getVendor(), Category.SELECT, baseJavaPackage);

        TypeSpec connection = createConnection("SomeConnection", file);

        createConnectionProvider(extensionBuilder);
        createOperationsContainer(extensionBuilder, file);

        TypeSpec build = extensionBuilder.build();

        extensionBuilder.withAdditionalClass(JavaType.create(connection, baseJavaPackage + ".internal"));

        extensionBuilder.writeJavaFiles(outputFolder);
    }

    private TypeSpec createConnection(String className, FileDescriptorProto file) {
        Builder builder = TypeSpec.classBuilder(className);
        builder.addModifiers(PUBLIC);
        ServiceDescriptorProto service = file.getService(0);
        String name = service.getName();
        ClassName futureStub = ClassName.get(baseJavaPackage, name + "Grpc." + name + "FutureStub");
        ClassName stub = ClassName.get(baseJavaPackage, name + "Grpc." + name + "Stub");
        ClassName grpc = ClassName.get(baseJavaPackage, name + "Grpc");

        FieldSpec channel = FieldSpec.builder(MANAGED_CHANNEL, "channel", Modifier.PRIVATE, Modifier.FINAL).build();
        FieldSpec serviceFutureStub = FieldSpec.builder(futureStub, "serviceFutureStub", Modifier.PRIVATE, Modifier.FINAL).build();
        FieldSpec serviceStub = FieldSpec.builder(stub, "serviceStub", Modifier.PRIVATE, Modifier.FINAL).build();
        FieldSpec scheduler = FieldSpec.builder(SCHEDULER, "scheduler", Modifier.PRIVATE, Modifier.FINAL).build();

        builder.addField(channel);
        builder.addField(serviceFutureStub);
        builder.addField(serviceStub);
        builder.addField(scheduler);

        builder.addMethod(MethodSpec
                .constructorBuilder()
                .addModifiers(PUBLIC)
                .addParameter(String.class, "host")
                .addParameter(int.class, "port")
                .addParameter(SCHEDULER, "scheduler")
                .addStatement("this($T.forAddress(host, port)" +
                        ".usePlaintext()" +
                        ".build(), scheduler)", MANAGED_CHANNEL_BUILDER)
                .build());

        builder.addMethod(MethodSpec
                .constructorBuilder()
                .addModifiers(PUBLIC)
                .addParameter(MANAGED_CHANNEL, "channel")
                .addParameter(SCHEDULER, "scheduler")
                .addStatement("this.channel = channel")
                .addStatement("this.serviceFutureStub = $T.newFutureStub(channel)", grpc)
                .addStatement("this.serviceStub = $T.newStub(channel)", grpc)
                .addStatement("this.scheduler = scheduler")
                .build());

        for (MethodDescriptorProto methodDescriptorProto : service.getMethodList()) {
            String methodName = methodName(methodDescriptorProto);
            MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(methodName);
            methodBuilder.addModifiers(PUBLIC);
            methodBuilder.returns(void.class);

            String[] split = methodDescriptorProto.getInputType().split("\\.");
            ClassName inputType = ClassName.get(baseJavaPackage, this.types.get(split[split.length - 1]).name);

            split = methodDescriptorProto.getOutputType().split("\\.");
            ClassName outputType = ClassName.get(baseJavaPackage, types.get(split[split.length - 1]).name);
            ClassName outputApiType = ClassName.get(apiPackage, types.get(split[split.length - 1]).name);

            if(methodDescriptorProto.getClientStreaming() && methodDescriptorProto.getServerStreaming()) {
                generateBiStreamingMethod(builder, methodName, methodBuilder, inputType, outputType, outputApiType, serviceStub);
            } else if(methodDescriptorProto.getClientStreaming()) {
                generateClientStreamingMethod(builder, methodName, methodBuilder, inputType, outputType, outputApiType, serviceStub);
            } else if (methodDescriptorProto.getServerStreaming()) {
                generateServerStreamingMethod(builder, methodName, methodBuilder, inputType, outputType, outputApiType, serviceStub);
            } else {
                generateNonStreamingMethod(builder, methodName, methodBuilder, inputType, outputType, outputApiType);
            }
        }


        return builder.build();
    }


//  public Iterator<StringWrapper> biStreaming(Iterator<com.mulesoft.connectors.helloworld.StringWrapper> input) {
//      streamTestTwo responseObserver = new streamTestTwo();
//      StreamObserver<com.mulesoft.connectors.helloworld.StringWrapper> stringWrapperStreamObserver = serviceStub.biStreaming(responseObserver);
//      scheduler.execute(() -> {
//          while (input.hasNext()) {
//              stringWrapperStreamObserver.onNext(input.next());
//          }
//          stringWrapperStreamObserver.onCompleted();
//      });
//
//      return responseObserver;
//  }
    private void generateBiStreamingMethod(Builder builder, String methodName, MethodSpec.Builder methodBuilder, ClassName inputType, ClassName outputType, ClassName outputApiType, FieldSpec serviceStub) {
        TypeSpec streamToIteratorClass = createStreamToIteratorClass(methodName, outputType, outputApiType);
        builder.addType(streamToIteratorClass);
        methodBuilder.addParameter(ParameterizedTypeName.get(ClassName.get(Iterator.class), inputType), "input");
        methodBuilder.returns(ParameterizedTypeName.get(ClassName.get(Iterator.class), outputApiType));

        methodBuilder.addStatement("$L iteratorObserver = new $L()", streamToIteratorClass.name, streamToIteratorClass.name);
        methodBuilder.addStatement("$T<$T> observer = $L.$L(iteratorObserver)", STREAM_OBSERVER, outputType, serviceStub.name, methodName);
        methodBuilder.addCode("scheduler.execute(() -> { \n");
        //TODO handle exceptions
        methodBuilder.addCode("while(input.hasNext()) { \n");
        methodBuilder.addStatement("observer.onNext(input.next())");
        methodBuilder.addCode("} \n");
        methodBuilder.addStatement("observer.onCompleted()");
        methodBuilder.addStatement("})");
        methodBuilder.addStatement("return iteratorObserver");

        builder.addMethod(methodBuilder.build());
    }

    private void generateClientStreamingMethod(Builder builder, String methodName, MethodSpec.Builder methodBuilder, ClassName inputType, ClassName outputType, ClassName outputApiType, FieldSpec serviceStub) {
        methodBuilder.addParameter(ParameterizedTypeName.get(ClassName.get(Iterator.class), inputType), "input");
        methodBuilder.addParameter(ParameterSpec.builder(ExtensionClassName.COMPLETION_CALLBACK(outputApiType, ClassName.get(Void.class)), "completionCallback").build());
        methodBuilder.addCode("      $T<$T> streamObserver = serviceStub.streamOnlyInput(new $T<$T>() {\n" +
                "          @Override\n" +
                "          public void onNext($T value) {\n" +
                "              completionCallback.success(Result.<$T, Void>builder().output($T.from(value)).build());\n" +
                "          }\n" +
                "\n" +
                "          @Override\n" +
                "          public void onError(Throwable t) {\n" +
                "              completionCallback.error(t);\n" +
                "          }\n" +
                "\n" +
                "          @Override\n" +
                "          public void onCompleted() {\n" +
                "\n" +
                "          }\n" +
                "      });\n" +
                "\n" +
                "       while (input.hasNext()) {\n" +
                "              try {\n" +
                "                  streamObserver.onNext(input.next());\n" +
                "              } catch (NoSuchElementException e ) {\n" +
                "              }\n" +
                "          }\n" +
                "      streamObserver.onCompleted();", STREAM_OBSERVER, inputType, STREAM_OBSERVER, outputType, outputType, outputApiType, outputApiType);
        builder.addMethod(methodBuilder.build());
    }


    private void generateServerStreamingMethod(Builder builder, String methodName, MethodSpec.Builder methodBuilder, ClassName inputType, ClassName outputType, ClassName outputApiType, FieldSpec serviceStub) {
        methodBuilder.returns(ParameterizedTypeName.get(ClassName.get(Iterator.class), outputApiType));

        String serviceStubName = "serviceStub";
        TypeSpec iteratorType = createStreamToIteratorClass(methodName, outputType, outputApiType);
        builder.addType(iteratorType);
        methodBuilder.addParameter(inputType, "input");

        methodBuilder.addStatement("$L iterator = new $L()",iteratorType.name, iteratorType.name);
        methodBuilder.addStatement("$L.$L(input, iterator)",serviceStub.name, methodName);
        methodBuilder.addStatement("return iterator");

        builder.addMethod(methodBuilder.build());
    }

    private TypeSpec createStreamToIteratorClass(String methodName, ClassName outputType, ClassName outputApiType) {
        return TypeSpec.classBuilder(methodName)
                    .addSuperinterface(ParameterizedTypeName.get(ClassName.get(Iterator.class), outputApiType))
                    .addSuperinterface(ParameterizedTypeName.get(STREAM_OBSERVER, outputType))
                    .addField(FieldSpec.builder(ParameterizedTypeName
                            .get(ClassName.get(BlockingQueue.class), outputApiType), "queue")
                            .initializer("new $T<>()", LinkedBlockingDeque.class)
                            .build())
                    .addField(FieldSpec.builder(ParameterizedTypeName.get(AtomicReference.class, Thread.class), "consumingThread").initializer("new $T<$T>()", AtomicReference.class, Thread.class).addModifiers(FINAL).build())
                    .addField(FieldSpec.builder(TypeName.BOOLEAN, "closed")
                            .initializer("false")
                            .build())
                    .addField(Throwable.class, "error")
                    .addMethod(MethodSpec.methodBuilder("hasNext")
                            .addModifiers(PUBLIC)
                            .returns(TypeName.BOOLEAN)
                            .addStatement("return !(queue.isEmpty() && closed)")
                            .build())
                    .addMethod(MethodSpec.methodBuilder("next")
                            .addModifiers(PUBLIC)
                            .returns(outputApiType)
                            .addCode("            try {\n" +
                                    "                if (error != null) {\n" +
                                    "                    throw new RuntimeException(error);\n" +
                                    "                }\n" +
                                    "                consumingThread.set(Thread.currentThread());\n" +
                                    "                $T take = queue.take();\n" +
                                    "                consumingThread.set(null);\n" +
                                    "                return take;" +
                                    "            } catch (InterruptedException e) {\n" +
                                    "                if (closed) {\n" +
                                    "                    throw new $T(\"No more elements\");\n" +
                                    "                } else {\n" +
                                    "                    throw new RuntimeException(e);\n" +
                                    "                }\n" +
                                    "            }", outputApiType, NoSuchElementException.class)
                            .build())
                    .addMethod(MethodSpec.methodBuilder("onNext")
                            .addModifiers(PUBLIC)
                            .addParameter(outputType, "value")
                            .addStatement("queue.add($T.from(value))", outputApiType)
                            .build())
                    .addMethod(MethodSpec.methodBuilder("onError")
                            .addModifiers(PUBLIC)
                            .addParameter(Throwable.class, "t")
                            .addStatement("error = t")
                            .addStatement("closed = true")
                            .build())
                    .addMethod(MethodSpec.methodBuilder("onCompleted")
                            .addModifiers(PUBLIC)
                            .addStatement("closed = true")
                            .addCode("if(consumingThread.get() != null) {")
                            .addCode("    consumingThread.get().interrupt();")
                            .addCode("}")
                            .build())

                    .build();
    }

    private void generateNonStreamingMethod(Builder builder, String methodName, MethodSpec.Builder methodBuilder, ClassName inputType, ClassName outputType, ClassName outputApiType) {
        methodBuilder.addParameter(inputType, "input");

        methodBuilder.addParameter(ParameterSpec.builder(ExtensionClassName.COMPLETION_CALLBACK(outputApiType, ClassName.get(Void.class)), "completionCallback").build());

        methodBuilder.addStatement("$T<$T> listenableFuture = serviceFutureStub.$L(input);", LISTENABLE_FUTURE,outputType, methodName);
        methodBuilder.addStatement("listenableFuture.addListener(() -> {\n" +
                "      try {\n" +
                "        completionCallback.success($T.<$T, Void>builder().output($T.from(listenableFuture.get())).build());\n" +
                "      } catch ($T | $T e) {\n" +
                "        completionCallback.error(e);\n" +
                "      }\n" +
                "    }, scheduler);", RESULT, outputApiType, outputApiType, InterruptedException.class, ExecutionException.class);

        builder.addMethod(methodBuilder.build());
    }

    private void createOperationsContainer(ExtensionBuilder extensionBuilder, FileDescriptorProto descriptorProto) {
        OperationContainerBuilder operationContainerBuilder = extensionBuilder.withOperationContainer();

        for (ServiceDescriptorProto serviceDescriptorProto : descriptorProto.getServiceList()) {
            for (MethodDescriptorProto methodDescriptorProto : serviceDescriptorProto.getMethodList()) {
                OperationBuilder operationBuilder = operationContainerBuilder.withOperation(methodName(methodDescriptorProto));
                operationBuilder.withConnection("connection");

                operationBuilder.returns(TypeName.VOID);
                String typeName = getTypeName(methodDescriptorProto.getInputType());
                String paramName = getParamName(typeName);
                ClassName inputApiType = ClassName.get(apiPackage, types.get(typeName).name);
                MethodParameterBuilder defaultParameter = MethodParameterBuilder
                        .builder(paramName, inputApiType)
                        .withAnnotation(AnnotationSpec.
                                builder(ExtensionClassName.CONTENT_ANNOTATION)
                                .build());


                String outputTypeName = getTypeName(methodDescriptorProto.getOutputType());
                ClassName param1 = ClassName.get(apiPackage, outputTypeName);
                if(methodDescriptorProto.getClientStreaming() && methodDescriptorProto.getServerStreaming()) {
//  public Iterator<StringWrapper> biStreaming(@Connection SomeConnection connection, @Content Iterator<StringWrapper> stringWrappers) {
//      return connection.biStreaming(new Iterator<com.mulesoft.connectors.helloworld.StringWrapper>() {
//          @Override
//          public boolean hasNext() {
//              return stringWrappers.hasNext();
//          }
//
//          @Override
//          public com.mulesoft.connectors.helloworld.StringWrapper next() {
//              return stringWrappers.next().to();
//          }
//      });
//
//  }
                    String[] split = methodDescriptorProto.getInputType().split("\\.");
                    ClassName inputType = ClassName.get(baseJavaPackage, this.types.get(split[split.length - 1]).name);

                    operationBuilder.returns(ParameterizedTypeName.get(ClassName.get(Iterator.class), param1));
                    String streamParam = paramName + "s";
                    operationBuilder.withParameter(MethodParameterBuilder
                            .builder(streamParam, ParameterizedTypeName.get(ClassName.get(Iterator.class), inputApiType))
                            .withAnnotation(AnnotationSpec.
                                    builder(ExtensionClassName.CONTENT_ANNOTATION)
                                    .build()));



                    operationBuilder.addCode("return connection.$L(new $T<$T>() {\n" +
                            "      @Override\n" +
                            "      public boolean hasNext() {\n" +
                            "        return $L.hasNext();\n" +
                            "      }\n" +
                            "\n" +
                            "      @Override\n" +
                            "      public $T next() {\n" +
                            "        return $L.next().to();\n" +
                            "      }\n" +
                            "    });", methodName(methodDescriptorProto), Iterator.class, inputType, streamParam, inputType, streamParam);

                } else if(methodDescriptorProto.getClientStreaming()) {
                    String[] split = methodDescriptorProto.getInputType().split("\\.");
                    ClassName inputType = ClassName.get(baseJavaPackage, this.types.get(split[split.length - 1]).name);

                    String streamParam = paramName + "s";
                    operationBuilder.withParameter(MethodParameterBuilder
                            .builder(streamParam, ParameterizedTypeName.get(ClassName.get(Iterator.class), inputApiType))
                            .withAnnotation(AnnotationSpec.
                                    builder(ExtensionClassName.CONTENT_ANNOTATION)
                                    .build()));
                    operationBuilder.withParameter("completionCallback", ExtensionClassName.COMPLETION_CALLBACK(param1, ClassName.get(Void.class)));

                    operationBuilder.addCode(" connection.$L(new $T<$T>() {\n" +
                            "      @Override\n" +
                            "      public boolean hasNext() {\n" +
                            "        return $L.hasNext();\n" +
                            "      }\n" +
                            "\n" +
                            "      @Override\n" +
                            "      public $T next() {\n" +
                            "        return $L.next().to();\n" +
                            "      }\n" +
                            "    }, completionCallback);", methodName(methodDescriptorProto), Iterator.class, inputType, streamParam, inputType, streamParam);
                } else if(methodDescriptorProto.getServerStreaming()) {
                    operationBuilder.
                            withParameter(defaultParameter);
                    operationBuilder.withParameter("streamingHelper", STREAMING_HELPER);
                    ParameterizedTypeName type = ParameterizedTypeName.get(ClassName.get(Iterator.class), param1);
                    operationBuilder.returns(type);
                    operationBuilder.withStatement(CodeBlock.of("Object returnValue = streamingHelper.resolveCursorProvider(connection.$L($L.to()))", methodName(methodDescriptorProto), paramName));
                    operationBuilder.addCode("    if(returnValue instanceof $T) {\n" +
                            "      return ($T) returnValue;\n" +
                            "    } else {\n" +
                            "      return (($T) returnValue).openCursor();\n" +
                            "    }", Iterator.class, type, CURSOR_ITERATOR_PROVIDER);
                } else {
                    operationBuilder.
                            withParameter(defaultParameter);
                    operationBuilder.withParameter("completionCallback", ExtensionClassName.COMPLETION_CALLBACK(param1, ClassName.get(Void.class)));
                    operationBuilder.withStatement(CodeBlock.of("connection.$L($L.to(),$L)",methodName(methodDescriptorProto), paramName, "completionCallback"));
                }

            }
        }

        TypeSpec build = operationContainerBuilder.build();

        JavaFile javaFile = JavaFile.builder(apiPackage, build)
                .build();

        try {
            javaFile.writeTo(System.out);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String getParamName(String typeName) {
        return typeName.substring(0,1).toLowerCase() + typeName.substring(1);
    }

    private String getTypeName(String inputType) {
        String[] split233 = inputType.split("\\.");
        return split233[split233.length - 1];
    }

    private static String methodName(MethodDescriptorProto methodDescriptorProto) {
        String name = methodDescriptorProto.getName();
        return name.substring(0,1).toLowerCase() + name.substring(1);
    }

    private void createConnectionProvider(ExtensionBuilder extensionBuilder) {
        ClassName connectionType = ClassName.get(extensionBuilder.getPackage(), "SomeConnection");
        ConnectionProviderBuilder connectionProviderBuilder = extensionBuilder.withConnectionProvider("SomeName", connectionType);

        connectionProviderBuilder.asCached();
        connectionProviderBuilder.withField(FieldSpec.builder(SCHEDULER_SERVICE, "schedulerService").addAnnotation(INJECT_ANNOTATION).build());
        FieldSpec scheduler = FieldSpec.builder(SCHEDULER, "scheduler").build();
        connectionProviderBuilder.withField(scheduler);
        connectionProviderBuilder.withParameter("host", ClassName.get(String.class));
        connectionProviderBuilder.withParameter("port", int.class);
        connectionProviderBuilder.withConnectMethod(CodeBlock.builder().addStatement("return new $T($L,$L,$L)", connectionType, "host", "port", "schedulerService.ioScheduler()").build());

        TypeSpec build = connectionProviderBuilder.build();

        JavaFile javaFile = JavaFile.builder(apiPackage, build)
                .build();

        try {
            javaFile.writeTo(System.out);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private void createMethod(DescriptorProto descriptorProto, File outputFolder) throws IOException {
        Builder builder = TypeSpec.classBuilder(descriptorProto.getName())
                .addModifiers(PUBLIC);

        parseFields(descriptorProto, builder);

        addTransformers(descriptorProto, builder);

        TypeSpec build = builder.build();

        types.put(build.name, build);

        JavaFile javaFile = JavaFile.builder(apiPackage, build)
                .build();

        javaFile.writeTo(outputFolder);

    }

    private void addTransformers(DescriptorProto descriptorProto, Builder builder) {
        ClassName className = ClassName.get(baseJavaPackage, descriptorProto.getName());
        ClassName returnType = ClassName.get(apiPackage, descriptorProto.getName());
        addFrom(descriptorProto, builder, className, returnType);
        addTo(descriptorProto, builder, returnType, className);

    }

    private static void addTo(DescriptorProto descriptorProto, Builder builder, ClassName className, ClassName returnType) {
        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder("to")
                .addModifiers(PUBLIC)
                .returns(returnType);

        methodBuilder.addStatement("$T.Builder builder = $T.newBuilder()", returnType, returnType);
        for (FieldDescriptorProto field : descriptorProto.getFieldList()) {
            if(field.getType().equals(Type.TYPE_MESSAGE)){
                if(field.getLabel().equals(LABEL_REPEATED)) {
                    methodBuilder.addStatement("builder.$L(this.$L().stream().map(p -> p.to()).collect($T.toList()))", getListSetterName(field.getName()), getterName(field.getName()), Collectors.class);
                } else {
                    methodBuilder.addStatement("builder.$L(this.$L().to())", setterName(field.getName()), getterName(field.getName()));
                }
            } else {
                if(field.getLabel().equals(LABEL_REPEATED)) {
                    methodBuilder.addStatement("builder.$L(this.$L())", getListSetterName(field.getName()), getterName(field.getName()));
                } else {
                    if(field.getType().equals(TYPE_BYTES)) {
                        methodBuilder.addStatement("builder.$L($T.copyFrom(this.$L()))", setterName(field.getName()),ByteString.class, getterName(field.getName()));
                    } else {
                        methodBuilder.addStatement("builder.$L(this.$L())", setterName(field.getName()), getterName(field.getName()));
                    }
                }
            }
        }
        methodBuilder.addStatement("return builder.build()");


        builder.addMethod(methodBuilder.build());
    }

    private void addFrom(DescriptorProto descriptorProto, Builder builder, ClassName className, ClassName returnType) {
        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder("from")
                .addModifiers(PUBLIC, Modifier.STATIC)
                .addParameter(className, "param")
                .returns(returnType);

        methodBuilder.addStatement("$T type = new $T()", returnType, returnType);
        for (FieldDescriptorProto field : descriptorProto.getFieldList()) {
            if(field.getType().equals(Type.TYPE_MESSAGE)){
                ClassName className1 = ClassName.get(apiPackage, getTypeName(field.getTypeName()));
                if(field.getLabel().equals(LABEL_REPEATED)) {
                    methodBuilder.addStatement("type.$L(param.$L.stream().map(p -> $T.from(p)).collect($T.toList()))", setterName(field.getName()), getterName(field.getName() + "List()"), className1, Collectors.class);
                } else {
                    methodBuilder.addStatement("type.$L($T.from(param.$L()))", setterName(field.getName()), className1, getterName(field.getName()));
                }
            } else {
                if(field.getLabel().equals(LABEL_REPEATED)) {
                    methodBuilder.addStatement("type.$L(param.$LList())", setterName(field.getName()), getterName(field.getName()));
                } else {
                    if(field.getType().equals(TYPE_BYTES)) {
                        methodBuilder.addStatement("type.$L(param.$L().newInput())", setterName(field.getName()), getterName(field.getName()));
                    } else {
                        methodBuilder.addStatement("type.$L(param.$L())", setterName(field.getName()), getterName(field.getName()));
                    }
                }
            }
        }
        methodBuilder.addStatement("return type");


        builder.addMethod(methodBuilder.build());
    }

    private void parseFields(DescriptorProto descriptorProto, Builder builder) {
        for (FieldDescriptorProto fieldDescriptorProto : descriptorProto.getFieldList()) {
            parseField(builder, fieldDescriptorProto);
        }
    }

    private void parseField(Builder builder, FieldDescriptorProto fieldDescriptorProto) {
        switch (fieldDescriptorProto.getType()) {
            case TYPE_STRING:
            {
                createProperty(builder, fieldDescriptorProto, String.class);
                break;
            }
            case TYPE_BOOL:
            {
                createProperty(builder, fieldDescriptorProto, TypeName.BOOLEAN);
                break;
            }
            case TYPE_BYTES: {
                createProperty(builder, fieldDescriptorProto, InputStream.class);
                break;
            }
            case TYPE_INT32:
            case TYPE_INT64:
            {
                createProperty(builder, fieldDescriptorProto, TypeName.INT);
                break;
            }
            case TYPE_MESSAGE:
            {
                createProperty(builder, fieldDescriptorProto, ClassName.get(apiPackage, getTypeName(fieldDescriptorProto.getTypeName())));
                break;
//                    FieldSpec build = FieldSpec.builder(TypeName.)
            }
            default:
                throw new RuntimeException(fieldDescriptorProto.getType() + "");
        }
    }

    private static void createProperty(Builder builder, FieldDescriptorProto fieldDescriptorProto, Class type) {
        createProperty(builder, fieldDescriptorProto, ClassName.get(type));
    }

    private static void createProperty(Builder builder, FieldDescriptorProto fieldDescriptorProto, TypeName type) {
        String name = fieldDescriptorProto.getName();

        //TODO support for lists
        if(fieldDescriptorProto.getLabel().equals(LABEL_REPEATED)){
            type = ParameterizedTypeName.get(ClassName.get(List.class), type);
        }

        FieldSpec build = FieldSpec.builder(type, name).build();
        builder.addField(build);

        builder.addMethod(MethodSpec
                .methodBuilder(setterName(name))
                .addModifiers(PUBLIC)
                .addParameter(type, name)
                .addStatement("this.$L = $L", name, name)
                .build());

        builder.addMethod(MethodSpec
                .methodBuilder(getterName(name))
                .addModifiers(PUBLIC)
                .returns(type)
                .addStatement("return this.$L", name)
                .build());
    }

    private static String getterName(String name) {
        return "get" + name.substring(0, 1).toUpperCase() + name.substring(1);
    }

    private static String setterName(String name) {
        return "set" + name.substring(0, 1).toUpperCase() + name.substring(1);
    }

    private static String getListSetterName(String name) {
        return "addAll" + name.substring(0, 1).toUpperCase() + name.substring(1);
    }

}
