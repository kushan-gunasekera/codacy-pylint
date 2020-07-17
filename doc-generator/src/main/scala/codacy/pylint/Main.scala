package codacy.pylint

import better.files.File

import scala.xml._
import scala.io.Source
import ujson._

import sys.process._
import scala.util.Using

object Main {
  val blacklist = Set("E0401")

  implicit class NodeOps(val node: Node) extends AnyVal {
    def hasClass(cls: String): Boolean = node \@ "class" == cls
  }

  def toMarkdown(html: String): String = {
    val result =
      for {
        file <- File.temporaryFile()
        _ = file.write(html)
        res = Seq("pandoc", "-f", "html", "-t", "markdown", file.pathAsString).!!
      } yield res
    result.get()
  }

  val docsPath = "../docs"

  val version: String = {
    val file = File(docsPath) / "patterns.json"
    val patterns = file.contentAsString
    val json = ujson.read(patterns)
    json("version").str
  }

  val htmlString = Using.resource {
    val url = new java.net.URL(
      s"https://pylint.pycqa.org/en/${version.split('.').init.mkString(".")}/technical_reference/features.html"
    )
    val connection = url.openConnection.asInstanceOf[java.net.HttpURLConnection]
    connection.setRequestProperty("User-agent", "Mozilla/5.0")
    Source.fromInputStream(connection.getInputStream)
  }(_.mkString)

  val html = XML.loadString(htmlString)

  val rules = for {
    ths <- html \\ "th"
    th <- ths
    name <- th.find(_.hasClass("field-name"))
  } yield name.text

  val bodies = for {
    tds <- html \\ "td"
    td <- tds
    name <- td.find(_.hasClass("field-body"))
  } yield name

  val pattern = """.*\((.+)\).*""".r

  val rulesNamesTitlesBodies = rules.zip(bodies).collect {
    case (rule @ pattern(ruleName), body) if !blacklist.contains(ruleName) =>
      (ruleName, rule.stripSuffix(":"), body)
  }

  val rulesNamesTitlesBodiesMarkdown = rulesNamesTitlesBodies.map {
    case (name, title, body) => (name, title, toMarkdown(body.toString))
  }

  def makePlainText(title: String, body: String): (String, String) = {
    val newLines = body.linesIterator.toList match {
      case title :: secondLine :: rest =>
        title.stripSuffix(".") + "." :: secondLine.capitalize :: rest
      case lines => lines
    }
    val descriptionText = newLines.mkString(" ")
    (title, descriptionText)
  }

  val rulesNamesTitlesBodiesPlainText = rulesNamesTitlesBodies.map {
    case (name, title, body) =>
      val (newTitle, newBody) = makePlainText(title, body.text)
      (name, newTitle, newBody)
  }

  val files = rulesNamesTitlesBodiesMarkdown.map {
    case (r, t, b) =>
      (
        File(docsPath) / "description" / s"$r.md",
        s"# $t${System.lineSeparator}$b"
      )
  }

  final case class Parameter(name: String, description: String, default: Value)

  val parameters = Map[String, Seq[Parameter]](
    "R0914" -> Seq(
      Parameter(
        "max-locals",
        "Maximum number of locals for function / method body.",
        15
      )
    ),
    "C0301" -> Seq(
      Parameter(
        "max-line-length",
        "Maximum number of characters on a single line.",
        120
      )
    ),
    "C0102" -> Seq(
      Parameter(
        "bad-names",
        "Bad variable names which should always be refused, separated by a comma.",
        "foo,bar,baz,toto,tutu,tata"
      )
    ),
    "C0103" ->
      Seq(
        Parameter(
          "argument-rgx",
          "Regular expression matching correct argument names. Overrides argument- naming-style.",
          "[a-z_][a-z0-9_]{2,30}$"
        ),
        Parameter(
          "attr-rgx",
          "Regular expression matching correct attribute names.",
          "[a-z_][a-z0-9_]{2,30}$"
        ),
        Parameter(
          "class-rgx",
          "Regular expression matching correct class names.",
          "[A-Z_][a-zA-Z0-9]+$"
        ),
        Parameter(
          "const-rgx",
          "Regular expression matching correct constant names.",
          "(([A-Z_][A-Z0-9_]*)|(__.*__))$"
        ),
        Parameter(
          "function-rgx",
          "Regular expression matching correct function names.",
          "[a-z_][a-z0-9_]{2,30}$"
        ),
        Parameter(
          "method-rgx",
          "Regular expression matching correct method names.",
          "[a-z_][a-z0-9_]{2,30}$"
        ),
        Parameter(
          "module-rgx",
          "Regular expression matching correct module names.",
          "(([a-z_][a-z0-9_]*)|([A-Z][a-zA-Z0-9]+))$"
        ),
        Parameter(
          "variable-rgx",
          "Regular expression matching correct variable names.",
          "[a-z_][a-z0-9_]{2,30}$"
        ),
        Parameter(
          "inlinevar-rgx",
          "Regular expression matching correct inline iteration names.",
          "[A-Za-z_][A-Za-z0-9_]*$"
        ),
        Parameter(
          "class-attribute-rgx",
          "Regular expression matching correct class attribute names.",
          "([A-Za-z_][A-Za-z0-9_]{2,30}|(__.*__))$"
        )
      )
  )

  def addPatternsParameters(obj: Obj, ruleName: String): Unit = {
    addParameters(
      obj,
      ruleName,
      param => Obj("name" -> param.name, "default" -> param.default)
    )
  }

  def addDescriptionParameters(obj: Obj, ruleName: String): Unit = {
    addParameters(
      obj,
      ruleName,
      param => Obj("name" -> param.name, "description" -> param.description)
    )
  }

  def addParameters(
      obj: Obj,
      ruleName: String,
      f: Parameter => Obj
  ): Unit = {
    for {
      params <- parameters.get(ruleName)
    } {
      obj("parameters") = params.map(f)
    }
  }

  val patterns = ujson.write(
    Obj(
      "name" -> "pylint",
      "version" -> version,
      "patterns" -> Arr.from(rulesNamesTitlesBodies.map {
        case (ruleName, _, _) =>
          val (category, subcategory) = getCategory(ruleName)

          val result = Obj(
            "patternId" -> ruleName,
            "level" -> {
              ruleName.headOption
                .map {
                  case 'C'       => "Info" // "Convention" non valid
                  case 'R'       => "Info" // "Refactor" non valid
                  case 'W' | 'I' => "Warning"
                  case 'E'       => "Error"
                  case 'F'       => "Error" // "Fatal" non valid
                  case _ =>
                    throw new Exception(s"Unknown error type for $ruleName")
                }
                .getOrElse(throw new Exception(s"Empty rule name"))
            },
            "category" -> category
          )
          subcategory.foreach(x => result("subcategory") = x)
          addPatternsParameters(result, ruleName)
          result
      })
    ),
    indent = 2
  )

  private def getCategory(patternId: String) = {
    val commandInjection = ("Security", Some("CommandInjection"))
    patternId match {
      case "W0123" => commandInjection
      case "W0122" => commandInjection
      case _       => ("CodeStyle", None)
    }
  }

  val description = ujson.write(
    Arr.from(rulesNamesTitlesBodiesPlainText.map {
      case (ruleName, title, body) =>
        val result =
          Obj("patternId" -> ruleName, "title" -> title, "description" -> body)
        addDescriptionParameters(result, ruleName)
        result
    }),
    indent = 2
  )

  def writeToFile(file: File, string: String): Unit = {
    file.write(s"${string}${System.lineSeparator}")
  }

  def main(args: Array[String]): Unit = {
    writeToFile(File(docsPath) / "patterns.json", patterns)
    writeToFile(
      File(docsPath) / "description" / "description.json",
      description
    )
    files
      .map { case (n, c) => (n, c.trim) }
      .foreach { case (file, content) => writeToFile(file, content) }
  }
}
