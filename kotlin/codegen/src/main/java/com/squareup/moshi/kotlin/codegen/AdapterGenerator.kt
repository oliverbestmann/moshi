/*
 * Copyright (C) 2018 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.moshi.kotlin.codegen

import com.squareup.kotlinpoet.ARRAY
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.NameAllocator
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import me.eugeniomarletti.kotlin.metadata.isDataClass
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.ProtoBuf.Visibility
import me.eugeniomarletti.kotlin.metadata.visibility
import java.lang.reflect.Type
import javax.lang.model.element.TypeElement

/** Generates a JSON adapter for a target type. */
internal class AdapterGenerator(
  target: TargetType,
  private val propertyList: List<PropertyGenerator>
) {
  private val className = target.name
  private val isDataClass = target.proto.isDataClass
  private val visibility = target.proto.visibility!!
  private val typeVariables = target.typeVariables

  private val nameAllocator = NameAllocator()
  private val adapterName = "${className.simpleNames.joinToString(separator = "_")}JsonAdapter"
  private val originalTypeName = target.element.asType().asTypeName()

  private val moshiParam = ParameterSpec.builder(
      nameAllocator.newName("moshi"),
      Moshi::class).build()
  private val typesParam = ParameterSpec.builder(
      nameAllocator.newName("types"),
      ARRAY.parameterizedBy(Type::class.asTypeName()))
      .build()
  private val readerParam = ParameterSpec.builder(
      nameAllocator.newName("reader"),
      JsonReader::class)
      .build()
  private val writerParam = ParameterSpec.builder(
      nameAllocator.newName("writer"),
      JsonWriter::class)
      .build()
  private val valueParam = ParameterSpec.builder(
      nameAllocator.newName("value"),
      originalTypeName.copy(nullable = true))
      .build()
  private val jsonAdapterTypeName = JsonAdapter::class.asClassName().parameterizedBy(originalTypeName)

  // selectName() API setup
  private val optionsProperty = PropertySpec.builder(
      nameAllocator.newName("options"), JsonReader.Options::class.asTypeName(),
      KModifier.PRIVATE)
      .initializer("%T.of(${propertyList.joinToString(", ") {
        CodeBlock.of("%S", it.jsonName).toString()
      }})", JsonReader.Options::class.asTypeName())
      .build()

  fun generateFile(generatedOption: TypeElement?): FileSpec {
    for (property in propertyList) {
      property.allocateNames(nameAllocator)
    }

    val result = FileSpec.builder(className.packageName, adapterName)
    result.addComment("Code generated by moshi-kotlin-codegen. Do not edit.")
    result.addType(generateType(generatedOption))
    return result.build()
  }

  private fun generateType(generatedOption: TypeElement?): TypeSpec {
    val result = TypeSpec.classBuilder(adapterName)

    generatedOption?.let {
      result.addAnnotation(AnnotationSpec.builder(it.asClassName())
          .addMember("value = [%S]", JsonClassCodegenProcessor::class.java.canonicalName)
          .addMember("comments = %S", "https://github.com/square/moshi")
          .build())
    }

    result.superclass(jsonAdapterTypeName)

    if (typeVariables.isNotEmpty()) {
      result.addTypeVariables(typeVariables)
    }

    // TODO make this configurable. Right now it just matches the source model
    if (visibility == Visibility.INTERNAL) {
      result.addModifiers(KModifier.INTERNAL)
    }

    result.primaryConstructor(generateConstructor())

    val typeRenderer: TypeRenderer = object : TypeRenderer() {
      override fun renderTypeVariable(typeVariable: TypeVariableName): CodeBlock {
        val index = typeVariables.indexOfFirst { it == typeVariable }
        check(index != -1) { "Unexpected type variable $typeVariable" }
        return CodeBlock.of("%N[%L]", typesParam, index)
      }
    }

    result.addProperty(optionsProperty)
    for (uniqueAdapter in propertyList.distinctBy { it.delegateKey }) {
      result.addProperty(uniqueAdapter.delegateKey.generateProperty(
          nameAllocator, typeRenderer, moshiParam, uniqueAdapter.name))
    }

    result.addFunction(generateToStringFun())
    result.addFunction(generateFromJsonFun())
    result.addFunction(generateToJsonFun())

    return result.build()
  }

  private fun generateConstructor(): FunSpec {
    val result = FunSpec.constructorBuilder()
    result.addParameter(moshiParam)

    if (typeVariables.isNotEmpty()) {
      result.addParameter(typesParam)
    }

    return result.build()
  }

  private fun generateToStringFun(): FunSpec {
    return FunSpec.builder("toString")
        .addModifiers(KModifier.OVERRIDE)
        .returns(String::class)
        .addStatement("return %S",
            "GeneratedJsonAdapter(${originalTypeName.rawType().simpleNames.joinToString(".")})")
        .build()
  }

  private fun jsonDataException(description: String, identifier: String, condition: String, reader: ParameterSpec): CodeBlock {
    return CodeBlock.of("%T(%T(%S).append(%S).append(%S).append(%N.path).toString())",
            JsonDataException::class, StringBuilder::class, description, identifier, condition, reader)
  }

  private fun generateFromJsonFun(): FunSpec {
    val resultName = nameAllocator.newName("result")

    val result = FunSpec.builder("fromJson")
        .addModifiers(KModifier.OVERRIDE)
        .addParameter(readerParam)
        .returns(originalTypeName)

    for (property in propertyList) {
      result.addCode("%L", property.generateLocalProperty())
      if (property.differentiateAbsentFromNull) {
        result.addCode("%L", property.generateLocalIsPresentProperty())
      }
    }

    result.addStatement("%N.beginObject()", readerParam)
    result.beginControlFlow("while (%N.hasNext())", readerParam)
    result.beginControlFlow("when (%N.selectName(%N))", readerParam, optionsProperty)

    propertyList.forEachIndexed { index, property ->
      if (property.differentiateAbsentFromNull) {
        result.beginControlFlow("%L -> ", index)
        if (property.delegateKey.nullable) {
          result.addStatement("%N = %N.fromJson(%N)",
              property.localName, nameAllocator[property.delegateKey], readerParam)
        } else {
          result.addCode("%N = %N.fromJson(%N) ?: throw·",
              property.localName, nameAllocator[property.delegateKey], readerParam)

          result.addCode(jsonDataException(
              "Non-null value '", property.localName, "' was null at ", readerParam))
        }
        result.addStatement("%N = true", property.localIsPresentName)
        result.endControlFlow()
      } else {
        if (property.delegateKey.nullable) {
          result.addStatement("%L -> %N = %N.fromJson(%N)",
              index, property.localName, nameAllocator[property.delegateKey], readerParam)
        } else {
          result.addCode("%L -> %N = %N.fromJson(%N) ?: throw·",
              index, property.localName, nameAllocator[property.delegateKey], readerParam)

          result.addCode(jsonDataException(
              "Non-null value '", property.localName, "' was null at ", readerParam))
        }
      }
    }

    result.beginControlFlow("-1 ->")
    result.addComment("Unknown name, skip it.")
    result.addStatement("%N.skipName()", readerParam)
    result.addStatement("%N.skipValue()", readerParam)
    result.endControlFlow()

    result.endControlFlow() // when
    result.endControlFlow() // while
    result.addStatement("%N.endObject()", readerParam)

    // Call the constructor providing only required parameters.
    var hasOptionalParameters = false
    result.addCode("«var %N = %T(", resultName, originalTypeName)
    var separator = "\n"
    for (property in propertyList) {
      if (!property.hasConstructorParameter) {
        continue
      }
      if (property.hasDefault) {
        hasOptionalParameters = true
        continue
      }
      result.addCode(separator)
      result.addCode("%N = %N", property.name, property.localName)
      if (property.isRequired) {
        result.addCode(" ?: throw·").addCode(jsonDataException(
            "Required property '", property.localName, "' missing at ", readerParam))
      }
      separator = ",\n"
    }
    result.addCode(")»\n", originalTypeName)

    // Call either the constructor again, or the copy() method, this time providing any optional
    // parameters that we have.
    if (hasOptionalParameters) {
      if (isDataClass) {
        result.addCode("«%1N = %1N.copy(", resultName)
      } else {
        result.addCode("«%1N = %2T(", resultName, originalTypeName)
      }
      separator = "\n"
      for (property in propertyList) {
        if (!property.hasConstructorParameter) {
          continue // No constructor parameter for this property.
        }
        if (isDataClass && !property.hasDefault) {
          continue // Property already assigned.
        }

        result.addCode(separator)
        when {
          property.differentiateAbsentFromNull -> {
            result.addCode("%2N = if (%3N) %4N else %1N.%2N",
                resultName, property.name, property.localIsPresentName, property.localName)
          }
          property.isRequired -> {
            result.addCode("%1N = %2N", property.name, property.localName)
          }
          else -> {
            result.addCode("%2N = %3N ?: %1N.%2N", resultName, property.name, property.localName)
          }
        }
        separator = ",\n"
      }
      result.addCode("»)\n")
    }

    // Assign properties not present in the constructor.
    for (property in propertyList) {
      if (property.hasConstructorParameter) {
        continue // Property already handled.
      }
      if (property.differentiateAbsentFromNull) {
        result.addStatement("%1N.%2N = if (%3N) %4N else %1N.%2N",
            resultName, property.name, property.localIsPresentName, property.localName)
      } else {
        result.addStatement("%1N.%2N = %3N ?: %1N.%2N",
            resultName, property.name, property.localName)
      }
    }

    result.addStatement("return %1N", resultName)
    return result.build()
  }

  private fun generateToJsonFun(): FunSpec {
    val result = FunSpec.builder("toJson")
        .addModifiers(KModifier.OVERRIDE)
        .addParameter(writerParam)
        .addParameter(valueParam)

    result.beginControlFlow("if (%N == null)", valueParam)
    result.addStatement("throw %T(%S)", NullPointerException::class,
        "${valueParam.name} was null! Wrap in .nullSafe() to write nullable values.")
    result.endControlFlow()

    result.addStatement("%N.beginObject()", writerParam)
    propertyList.forEach { property ->
      result.addStatement("%N.name(%S)", writerParam, property.jsonName)
      result.addStatement("%N.toJson(%N, %N.%L)",
          nameAllocator[property.delegateKey], writerParam, valueParam, property.name)
    }
    result.addStatement("%N.endObject()", writerParam)

    return result.build()
  }
}
