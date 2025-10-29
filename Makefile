SHELL := /bin/bash

APP_NAME    := k8s-spark-submitter
VERSION     := $(shell mvn help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null || echo "1.0.0")
SPARK_IMAGE ?= docker.io/library/spark:4.0.1
IMAGE_TAG   ?= $(APP_NAME):$(VERSION)

# ------------------------------------------------------------------------------
# Build
# ------------------------------------------------------------------------------

.PHONY: build test package clean image run

build:
	mvn clean compile

test:
	mvn test

package:
	mvn clean package -DskipTests -Dmaven.test.skip=true

clean:
	mvn clean

# ------------------------------------------------------------------------------
# Docker
# ------------------------------------------------------------------------------

image: package
	docker build --build-arg SPARK_IMAGE=$(SPARK_IMAGE) -t $(IMAGE_TAG) .

image-only:
	docker build --build-arg SPARK_IMAGE=$(SPARK_IMAGE) -t $(IMAGE_TAG) .

# ------------------------------------------------------------------------------
# Run
# ------------------------------------------------------------------------------

run: package
	java -Dloader.path=/opt/spark/jars -jar target/$(APP_NAME)-$(VERSION).jar

# ------------------------------------------------------------------------------
# Help
# ------------------------------------------------------------------------------

.DEFAULT_GOAL := help

help:
	@echo "Usage: make <target>"
	@echo ""
	@echo "Build:"
	@echo "  build       Compile the project"
	@echo "  test        Run tests"
	@echo "  package     Build JAR (skip tests)"
	@echo "  clean       Clean build artifacts"
	@echo ""
	@echo "Docker:"
	@echo "  image       Build JAR and Docker image"
	@echo "  image-only  Build Docker image (assumes JAR exists)"
	@echo ""
	@echo "Run:"
	@echo "  run         Build and run locally"
	@echo ""
	@echo "Variables:"
	@echo "  SPARK_IMAGE  Base Spark image (default: $(SPARK_IMAGE))"
	@echo "  IMAGE_TAG    Docker image tag (default: $(IMAGE_TAG))"
