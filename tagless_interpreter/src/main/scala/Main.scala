import scala.quoted._

//repr[_] is a wrapper
trait Symantics[repr[_]] {
  def int(x: Int): repr[Int]
  def bool(b: Boolean): repr[Boolean]

  def lam[A: Type, B: Type](f: repr[A] => repr[B]): repr[A => B]
  def app[A, B](f: repr[A => B], arg: repr[A]): repr[B]
  def fix[A: Type, B: Type](f: repr[A => B] => repr[A => B]): repr[A => B]
  //tried fix(f: => (...)) but it doesn't change anything

  def add(x: repr[Int], y: repr[Int]): repr[Int]
  def mul(x: repr[Int], y: repr[Int]): repr[Int]
  def leq(x: repr[Int], y: repr[Int]): repr[Boolean]
  def if_[A](cond: repr[Boolean], e1: => repr[A], e2: => repr[A]): repr[A]
  // 1) def if_[A](cond: repr[Boolean], e1: Unit => repr[A], e2: Unit => repr[A]): repr[A]
  // 2) def if_[A](cond: repr[Boolean], e1: repr[Unit => A], e2: repr[Unit => A]): repr[A]
  // 3) I used the real if statement
  // How could I splice the Expr[] of e1 and e2 but not evaluate them, how to keep them as CBN ?

}

object Main {
  implicit val toolbox: scala.quoted.Toolbox = scala.quoted.Toolbox.make

  //Tagless interpreter, no wrapper
  type Id[A] = A
  val eval: Symantics[Id] = new Symantics[Id] {
    override def int(x: Int): Int= x
    override def bool(b: Boolean): Boolean = b

    override def lam[A: Type, B: Type](f: A => B): A => B = f
    override def app[A, B](f: A => B, arg: A): B = f(arg)
    override def fix[A: Type, B: Type](f: (A => B) => (A => B)): A => B = f(fix(f))(_: A) //(x: A) => f(fix(f))(x)

    override def add(x: Int, y: Int): Int = x + y
    override def mul(x: Int, y: Int): Int = x * y
    override def leq(x: Int, y: Int): Boolean = x <= y
    //e1 and e2 must be CBN because of 'fix' : if either e1 or e2 is a recursion then' it'll be evaluated immediately and so on -> infinite loop
    override def if_[A](cond: Boolean, e1: => A, e2: => A): A = if (cond) e1 else e2
  }


  //Staged tagless interpreter
  val evalQuoted: Symantics[Expr] = new Symantics[Expr] {
    override def int(x: Int): Expr[Int] = x.toExpr
    override def bool(b: Boolean): Expr[Boolean] = b.toExpr

    override def lam[A: Type, B: Type](f: Expr[A] => Expr[B]): Expr[A => B] =  '{ (x: A) => ~(f('(x))) }
    override def app[A, B](f: Expr[A => B], arg: Expr[A]): Expr[B] = f(arg) //'{ (~f)(~arg) }, use .asFunction()
    override def fix[A: Type, B: Type](f: Expr[A => B] => Expr[A => B]): Expr[A => B] = lam((x: Expr[A]) => f(fix(f))(x)) //'{ (~f(fix(f)))(_: A) }

    override def add(x: Expr[Int], y: Expr[Int]): Expr[Int] = '{ ~x + ~y }
    override def mul(x: Expr[Int], y: Expr[Int]): Expr[Int] = '{ ~x * ~y }
    override def leq(x: Expr[Int], y: Expr[Int]): Expr[Boolean] = '{ ~x <= ~y }
    override def if_[A](cond: Expr[Boolean], e1: => Expr[A], e2: => Expr[A]): Expr[A] = '{ if(~cond) ~e1 else ~e2 }
    // 1) if_[A](cond: Expr[Boolean], e1: Unit => Expr[A], e2: Unit => Expr[A]): Expr[A] = '{ if(~cond) ~(e1(0)) else ~(e2(0)) }
    //      I can't do ~(e1) because it's not possible to splice "Unit=>quoted.Expr"
    //      I can't do ~(e1()) because there is a missing argument
    //      So I give him a dummy argument : ~(e1(0)) but I'm obviously back with a StackOverflowError
    // 2) if_[A](cond: Expr[Boolean], e1: Expr[Unit => A], e2: => Expr[Unit => A]): Expr[A] = '{ if(~cond) (~e1)(0) else (~e2)(0) }
    //      I can't do (~e1)() because there is a missing argument
    //      I can give it a dummy argument (~e1)(0) but I'd have to change most of the functions to take "Unit => ..." as arguments so it's not a good solution
    // I wanted to see e2 in "if_" so I used "e2.show" but it threw an ArrayOutOfBoundsexception
  }


  def main(args: Array[String]): Unit = {

    // TEST STAGED
    import evalQuoted._

    //(b=b)(true)
    val t1 = app(lam((b: Expr[Boolean]) => b), bool(true))
    println("======================")
    println("show : " + t1.show)
    println("res : " + t1.run)
    println("======================")

    //(x*x)(4)
    val t2 = app(lam((x: Expr[Int]) => mul(x, x)), int(4))
    println("show : " + t2.show)
    println("res : " + t2.run)
    println("======================")

    //(if(x <= 1) true else false)(1)
    val t3 = app(
      lam((x: Expr[Int]) => if_(leq(x, int(1)), bool(true), bool(false))),
      // 1) lam((x: Expr[Int]) => if_(leq(x, int(1)), (u: Unit) => bool(true), (u: Unit) => bool(false))),
      // 2) lam((x: Expr[Int]) => if_(leq(x, int(1)), bool((u: Unit) => true), bool((u: Unit) => false))),
      //    I call "bool((u: Unit) => true)" but I'd have to change the function "bool(b: Boolean): Expr[Boolean]" so I don't think it is the right solution
      int(1))
    println("show : " + t3.show)
    println("res : " + t3.run)
    println("======================")

    //factorial(5),
    val t4 = app(fix((f: Expr[Int => Int]) =>
              '{ (n: Int) => ~if_(leq('(n), int(1)), '(n), mul(f(add('(n), int(-1))), '(n))) }), //same as t5: 'lam( (n: Expr[Int]) => if_(leq(n, int(1)), n, mul(f(add(n, int(-1))), n)))'
              // 1) '{ (n: Int) => ~if_(leq('(n), int(1)), (u: Unit) => '(n), (u: Unit) => mul(f(add('(n), int(-1))), '(n))) }),
              // 2) too ugly to print there
              // 3) with the real if-statement : '{ (n: Int) => if(n <= 1) n else ~mul('(n), f(add('(n), int(-1)))) }), still got the error ---> more likely that the problem comes from "fix"
              int(5))
    println("show : " + t4.show)
    println("res : " + t4.run)
    println("======================")

    /** t4 = t5
    val t5 = app(fix((f: Expr[Int => Int]) =>
            lam( (n: Expr[Int]) => if_(leq(n, int(1)), n, mul(f(add(n, int(-1))), n)))),
            int(5))
    println("show : " + t5.show)
    println("res : " + t5.run)
    println("======================")*/

    ////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////
/**
    //TEST ID

    import eval._

    //(b=b)(true)
    val t1 = app(lam((b: Boolean) => b), bool(true))
    println("======================")
    println("res : " + t1)
    println("======================")

    //(x*x)(4)
    val t2 = app(lam((x: Int) => mul(x, x)), int(4))
    println("res : " + t2)
    println("======================")

    //(if(x <= 1) 0 else (x-1)*(3*4))(2)
    val t3 = app(
      lam((x: Int) => if_(leq(x, int(1)), int(0), mul(add(x, -1), mul(3, 4)))),
      int(2))
    println("res : " + t3)
    println("======================")

    //factorial(5)
    val t4 = app(fix((f: Int => Int) =>
            lam((n: Int) => if_(leq(n, int(1)), n, mul(f(add(n, -1)), n)))),
            int(5))
    println("res : " + t4)
    println("======================")*/

  }

}
