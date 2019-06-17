package com.mulesoft.connectors.grpc.examples.server;/*
 * Copyright 2015 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.mulesoft.connectors.helloworld.Car;
import com.mulesoft.connectors.helloworld.CarCreation;
import com.mulesoft.connectors.helloworld.CocherasServiceGrpc;
import com.mulesoft.connectors.helloworld.People;
import com.mulesoft.connectors.helloworld.Person;
import com.mulesoft.connectors.helloworld.PersonCreation;
import com.mulesoft.connectors.helloworld.PersonGetRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

public class HelloWorldServer {
  private static final Logger logger = Logger.getLogger(HelloWorldServer.class.getName());

  private Server server;

  private void start() throws IOException {
    /* The port on which the server should run */
    int port = 5555;
    server = ServerBuilder.forPort(port)
        .addService(new GreeterImpl())
        .build()
        .start();
    logger.info("Server started, listening on " + port);
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        // Use stderr here since the logger may have been reset by its JVM shutdown hook.
        System.err.println("*** shutting down gRPC server since JVM is shutting down");
        HelloWorldServer.this.stop();
        System.err.println("*** server shut down");
      }
    });
  }

  private void stop() {
    if (server != null) {
      server.shutdown();
    }
  }

  /**
   * Await termination on the main thread since the grpc library uses daemon threads.
   */
  private void blockUntilShutdown() throws InterruptedException {
    if (server != null) {
      server.awaitTermination();
    }
  }

  /**
   * Main launches the server from the command line.
   */
  public static void main(String[] args) throws IOException, InterruptedException {
    final HelloWorldServer server = new HelloWorldServer();
    server.start();
    server.blockUntilShutdown();
  }

  static class GreeterImpl extends CocherasServiceGrpc.CocherasServiceImplBase {

    List<Person> people = new ArrayList<>();

    @Override
    public void getPeople(PersonGetRequest request, StreamObserver<People> responseObserver) {
      People his = People
              .newBuilder()
              .setPerson(people.get(0))
              .addRandomValues("his")
              .addAllPeople(people)
              .build();
      responseObserver.onNext(his);
      responseObserver.onCompleted();
    }

    @Override
    public void registerCar(CarCreation request, StreamObserver<Car> responseObserver) {
      super.registerCar(request, responseObserver);
    }

    @Override
    public void registerPerson(PersonCreation request, StreamObserver<Person> responseObserver) {
      System.out.println(request);

      Person person = Person.newBuilder()
              .setName(request.getName())
              .setId(people.size())
              .setLastname(request.getLastname())
              .build();

      people.add(person);

      responseObserver.onNext(person);
      responseObserver.onCompleted();
    }

    @Override
    public void getPerson(PersonGetRequest request, StreamObserver<Person> responseObserver) {
      int id = request.getId();
      responseObserver.onNext(people.get(id));
      responseObserver.onCompleted();
    }
  }
}
