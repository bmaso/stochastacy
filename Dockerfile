# Use an official Scala runtime
FROM amazoncorretto:21

# Set working directory
WORKDIR /app

# Copy the compiled JAR (build with sbt assembly)
COPY target/scala-3.3.1/stochastacy-assembly-0.0.1.jar /app/stochastacy.jar

# Expose the application's port
EXPOSE 8080

# Run the application
CMD ["java", "-jar", "stochastacy.jar"]
