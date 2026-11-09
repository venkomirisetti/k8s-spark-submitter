SHELL := /bin/bash

# Spark version module to build
SPARK_MODULE  ?= spark-4.0
MVN           := ./mvnw -f $(SPARK_MODULE)/pom.xml
# Spark line of the module, e.g. spark-4.0 -> 4.0
SPARK_LINE    := $(patsubst spark-%,%,$(SPARK_MODULE))
ARTIFACT      := spark-submitter-$(SPARK_LINE)
VERSION       := $(shell ./mvnw -f $(SPARK_MODULE)/pom.xml help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null || echo "1.0.0")
# Read a property from the module pom once, on first use
pom_property   = $(shell $(MVN) help:evaluate -Dexpression=$(1) -q -DforceStdout 2>/dev/null)
SPARK_IMAGE   ?= $(eval SPARK_IMAGE := $(call pom_property,spark.image))$(SPARK_IMAGE)
SPARK_VERSION ?= $(eval SPARK_VERSION := $(call pom_property,spark.version))$(SPARK_VERSION)
BUILD_NUMBER  ?= 1
DOCKER_REPO   ?= venkomirisetti/k8s-spark-submitter
IMAGE_TAG     ?= $(DOCKER_REPO):$(SPARK_VERSION)-$(BUILD_NUMBER)

# ------------------------------------------------------------------------------
# Build
# ------------------------------------------------------------------------------

.PHONY: build test package clean image image-only push print-spark-version run

build:
	$(MVN) clean compile

test:
	$(MVN) test

package:
	$(MVN) clean package -DskipTests -Dmaven.test.skip=true

clean:
	$(MVN) clean

# ------------------------------------------------------------------------------
# Docker
# ------------------------------------------------------------------------------

image: package
	docker build --build-arg SPARK_IMAGE=$(SPARK_IMAGE) --build-arg SPARK_MODULE=$(SPARK_MODULE) -t $(IMAGE_TAG) .

image-only:
	docker build --build-arg SPARK_IMAGE=$(SPARK_IMAGE) --build-arg SPARK_MODULE=$(SPARK_MODULE) -t $(IMAGE_TAG) .

push: image
	docker tag $(IMAGE_TAG) $(DOCKER_REPO):$(SPARK_VERSION)-latest
	docker tag $(IMAGE_TAG) $(DOCKER_REPO):$(SPARK_LINE)-$(BUILD_NUMBER)
	docker tag $(IMAGE_TAG) $(DOCKER_REPO):$(SPARK_LINE)-latest
	docker push $(IMAGE_TAG)
	docker push $(DOCKER_REPO):$(SPARK_VERSION)-latest
	docker push $(DOCKER_REPO):$(SPARK_LINE)-$(BUILD_NUMBER)
	docker push $(DOCKER_REPO):$(SPARK_LINE)-latest

print-spark-version:
	@echo $(SPARK_VERSION)

# ------------------------------------------------------------------------------
# Run
# ------------------------------------------------------------------------------

run: package
	java -Dloader.path=/opt/spark/jars -jar $(SPARK_MODULE)/target/$(ARTIFACT)-$(VERSION).jar

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
	@echo "  push        Build, tag, and push to Docker Hub"
	@echo ""
	@echo "Run:"
	@echo "  run         Build and run locally"
	@echo ""
	@echo "Variables:"
	@echo "  SPARK_IMAGE      Base Spark image (default: $(SPARK_IMAGE))"
	@echo "  DOCKER_REPO      Docker Hub repo (default: $(DOCKER_REPO))"
	@echo "  SPARK_VERSION    Spark version in tag (default: $(SPARK_VERSION))"
	@echo "  SPARK_MODULE     Version module to build (default: $(SPARK_MODULE))"
	@echo "  BUILD_NUMBER     Build number in tag (default: $(BUILD_NUMBER))"
	@echo "  IMAGE_TAG        Full image tag (default: $(IMAGE_TAG))"
