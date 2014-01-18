package pl.project13.scala.akka.raft

final case class Term(termNr: Long) extends AnyVal {
  def +(n: Long): Term = Term(termNr + n)

  def <(otherTerm: Term): Boolean = this.termNr < otherTerm.termNr

  def >=(otherTerm: Term): Boolean = this.termNr >= otherTerm.termNr
}

object Term {
  val Zero = Term(0)
}

