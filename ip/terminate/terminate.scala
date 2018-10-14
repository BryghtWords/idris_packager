package ip

import ip.codereporting._

package object terminate {

  private def execute(label: String, message: String, exitCode: Int)(implicit cr: CodeReport): Nothing = {
    println()
    println("─" * 55)
    println()
    println(label)
    println(s"    ${cr.file}:${cr.line}")
    println()
    println("Error message:")
    scala.io.Source.fromString(message).getLines.foreach(l => println(s"    $l"))
    println()
    println("Terminating the program now")
    sys.exit(exitCode)
  }

  def impossible(message: String, exitCode: Int = 10)(implicit cr: CodeReport): Nothing =
    execute("An IMPOSSIBLE bit of code has been executed at:", message, exitCode)(cr)

  def fatal(message: String, exitCode: Int = 20)(implicit cr: CodeReport): Nothing =
    execute("A FATAL error has occurred at:", message, exitCode)(cr)
}
