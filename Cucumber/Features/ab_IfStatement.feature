Feature: The if statement

  The if-control-structure provides us with the ability to program conditional operations. Later we
  will cover the switch/case statement, but we postpone that until we have the basics of the T
  language introduced.


Scenario: The if-statement allows alternative definitions for values

    A value name has only one value for the whole range of its scope, but that value may be selected
    from different options, depending upon the runtime conditions of the state.

  * A valid T Language run unit is
    """
    class Rates {

    double rate;

    double standardRate' = 0.05;
    double discountRate' = 0.15;

    boolean hasDiscount;

    void setRate() {
      if ('hasDiscount) rate' = discountRate';
                   else rate' = standardRate';
    // a natural logic for if-then-else statements
    means (   'hasDiscount ==> rate' = discountRate'
          & ! 'hasDiscount ==> rate' = standardRate'
          );
    }
    // an equivalent logic
    means ( (   'hasDiscount & rate' = discountRate')
          | ( ! 'hasDiscount & rate' = standardRate')
          );

    } // end class
    """

Scenario: Any value set in one branch of an if-statement must be set in both

    For a value name to be available to an operation or logic statement later in its scope, it must
    be defined along all paths to that statement. For an if-statement, this means that it must be
    defined in both branches.

  When an invalid run unit is
    """
    class Rates_1X {

    double rate;
    double reportRate;

    double standardRate' = 0.05;
    double discountRate' = 0.15;

    boolean hasDiscount;

    void setRate() {
      rate'standard = standardRate';
      if ('hasDiscount)
        rate' = discountRate';
      else ;
        // attempting to let rate variable defalt to rate'standard previous value
        // but the rate' value is not defined
      reportRate' = rate'; // the problem is not discovered here
    }
    means ( (   'hasDiscount & reportRate' = discountRate')
          | ( ! 'hasDiscount & reportRate' = standardRate')
              // no proof that reportRate' = standardRate' can be constructed without the missing
              // definition of rate' in the else-clause when 'hasDiscount = false
          );

    } // end class
    """

    Then an error message contains
    """
    value name rate' is not defined for the else-branch of the if statement
    """

Scenario: An if-then-statement, without the else clause, cannot set values in both conditions

  The then-only version of the if-statement becomes much less useful because value names must be
  defined under all cases. (However, a single branch of an if statement may be used if no value
  names are defined, or if the new value names are never relied upon for status statements; perhaps
  writing to a log file would be a good example, but even that would not apply if you wanted the
  accuracy of the log file to be part of the specification.)

  When an invalid run unit is
    """
    class Pair_1X {

    int a;
    int b;

    void invalidTwoSort() {
      if ('b < 'a) {
        a' = 'b;
        b' = 'a;
      } // else they are already in order
    }
    means (a' <= b');

    } // end class
    """

    Then an error message contains
    """
    The code does not support the proof of the statement: a' <= b'
    """

Scenario: The solution is to always include definitions of value names in all branches

  The definitions in the else branch are often trivial, and the compiler omits them in the generated
  code. This may be simplified in future versions if a suitable symbol or keyword can be found,
  perhaps "default", or as a pseudo-function like "default(a', b')", or even as a default-clause
  that is an alternative to the else-clause.

  But a valid run unit is
    """
    class Pair_2 {

    int a;
    int b;

    void invalidTwoSort() {
      if ('b < 'a) {
        a' = 'b;
        b' = 'a;
      } else {
        // When we include the definition of both a' and b' in both branches
        // the prover has no problem

        a' = 'a; // implemented as a no-op
        b' = 'b; // implemented as a no-op
      }
    }
    means (a' <= b');

    } // end class
    """


  Scenario: A more complex example - ThreeSort

    This version of sorting three items minimizes copying in each case.

    The means-statement is used to good effect to keep track of the state determined so far. If
    there are mistakes in programming, the final means statement for the threeSort() method could be
    copied to the end of suspected cases to see which one is at fault.

    The implicit local variable that TrueJ creates is often helpful, but in one case we need to use a different ordering of the code than our preferred one to avoid creating two implicit local variables.

  * A valid T Language run unit is
    """
    class Triplet {

    int a;
    int b;
    int c;

    void threeSort() {
      if ('a <= 'b ) {
        if ('b <= 'c) { // already in order
          a' = 'a;                          // no-op
          b' = 'b;                          // no-op
          c' = 'c;                          // no-op
        } else { // 'a & 'c both <= 'b
          if ('a <= c') {
            means ('a <= 'c && 'c <= 'b); // 'a <= 'c <= 'b);
            a' = 'a;                        // no-op
            b' = 'c;
            c' = 'b;
          } else {
            means ('c < 'a && 'a <= 'b); // 'c < 'a <= 'b);
            a' = 'c;
            c' = 'b; // definition of c' and b' swapped to minimize implicit local variables
            b' = 'a;
          }
        }
      } else { // 'b < 'a
        if ('a <= 'c) { // 'b < 'a <= 'c
          means ('b < 'a && 'a <= 'c); // 'b < 'a <= 'c);
          a' = 'b;
          b' = 'a;
          c' = 'c;                          // no-op
        } else {          // 'b & 'c both <= 'a
          if ('b <= c') {
            means ('b <= 'c && 'c < 'a); // 'b <= 'c < 'a
            a' = 'b;
            b' = 'c;
            c' = 'a;
          } else { // 'c < 'b < 'a
            means ('c < 'b && 'b < 'a); // 'c < 'b < 'a);
            a' = 'c;
            b' = 'b;                        // no-op
            c' = 'b;
          }
        }
      }
    }
    means (a' <= b' && b' <= c');

    } // end class
    """