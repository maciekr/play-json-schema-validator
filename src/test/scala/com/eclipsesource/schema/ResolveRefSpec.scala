package com.eclipsesource.schema

import com.eclipsesource.schema.internal.{Context, RefResolver}
import com.eclipsesource.schema.test.JSONSource
import org.specs2.mutable.Specification
import play.api.data.mapping.Path


class ResolveRefSpec extends Specification {

  "Relative ref" should {

    val schema = JSONSource.schemaFromString(
      """{
        |"$ref": "http://json-schema.org/draft-04/schema#"
                }""".stripMargin).get

    "be resolvable via " in {

      val context = Context(Path, schema, Set.empty)
      val updatedRoot = RefResolver.replaceRefs(context)(schema)
      val resolved: Option[SchemaType] = RefResolver.resolveRef("#/definitions/schemaArray", context.copy(root = updatedRoot))
      resolved must beSome.which(t => t.isInstanceOf[SchemaArray])
    }

    "resolve ref" in {
      val context = Context(Path, schema, Set.empty)
      val updatedRoot = RefResolver.replaceRefs(context)(schema)
      val resolved = RefResolver.resolveRef("#/properties/anyOf", context.copy(root = updatedRoot))
      resolved must beSome.which(t => t.isInstanceOf[SchemaArray])
    }

  }

}
