---
sidebar_position: 99
---

# Examples

Real programs that combine everything you've learned.

## FizzBuzz

Classic programming challenge:

```ink title="fizzbuzz.ink"
// FizzBuzz: print numbers 1-20, but:
// - Multiples of 3 print "Fizz"
// - Multiples of 5 print "Buzz"
// - Multiples of both print "FizzBuzz"

for i in 1..21 {
    if i % 15 == 0 {
        print("FizzBuzz")
    } else if i % 3 == 0 {
        print("Fizz")
    } else if i % 5 == 0 {
        print("Buzz")
    } else {
        print(i)
    }
}
```

## Factorial

Calculate factorials with recursion:

```ink title="factorial.ink"
fn factorial(n) {
    if n <= 1 {
        return 1
    }
    return n * factorial(n - 1)
}

for i in 1..11 {
    print("${i}! = ${factorial(i)}")
}
// 1! = 1
// 2! = 2
// 3! = 6
// ...
// 10! = 3628800
```

## Bank Account

OOP example with classes:

```ink title="bank.ink"
class BankAccount {
    init(owner) {
        this.owner = owner
        this.balance = 0
        this.transactions = []
    }

    deposit(amount) {
        if amount <= 0 {
            print("Deposit must be positive")
            return false
        }
        this.balance = this.balance + amount
        this.transactions.push("Deposited ${amount}")
        return true
    }

    withdraw(amount) {
        if amount <= 0 {
            print("Withdrawal must be positive")
            return false
        }
        if amount > this.balance {
            print("Insufficient funds")
            return false
        }
        this.balance = this.balance - amount
        this.transactions.push("Withdrew ${amount}")
        return true
    }

    getBalance() {
        return this.balance
    }

    showHistory() {
        print("${this.owner}'s Account:")
        print("Balance: ${this.getBalance()}")
        print("Transactions:")
        for tx in this.transactions {
            print("  - ${tx}")
        }
    }
}

let account = BankAccount("Alice")
account.deposit(1000)
account.deposit(500)
account.withdraw(200)
account.withdraw(2000)  // Should fail - insufficient funds
account.showHistory()
```

## To-Do List

Building a simple task manager:

```ink title="todos.ink"
class TodoList {
    init() {
        this.tasks = []
    }

    add(task) {
        this.tasks.push(task)
        print("Added: ${task}")
    }

    complete(index) {
        if index < this.tasks.size() {
            let task = this.tasks.get(index)
            print("Completed: ${task}")
            this.tasks.set(index, null)
        }
    }

    show() {
        print("=== To-Do List ===")
        let shown = 0
        for i in 0..this.tasks.size() {
            let task = this.tasks.get(i)
            if task != null {
                print("${shown + 1}. [ ] ${task}")
                shown = shown + 1
            }
        }
        if shown == 0 {
            print("All done!")
        }
    }
}

let todos = TodoList()
todos.add("Learn ink")
todos.add("Build a project")
todos.add("Write documentation")
todos.show()
todos.complete(0)
todos.show()
```

## Fibonacci Sequence

Generate the famous sequence:

```ink title="fibonacci.ink"
fn fibonacci(n) {
    if n <= 1 {
        return n
    }
    return fibonacci(n - 1) + fibonacci(n - 2)
}

// Print first 10 Fibonacci numbers
for i in 0..10 {
    print(fibonacci(i))
}
// 0, 1, 1, 2, 3, 5, 8, 13, 21, 34
```

## Temperature Converter

Utility program:

```ink title="temps.ink"
class Temperature {
    init(celsius) {
        this.celsius = celsius
    }

    toFahrenheit() {
        return this.celsius * 9 / 5 + 32
    }

    toKelvin() {
        return this.celsius + 273.15
    }

    describe() {
        return "${this.celsius}C = ${this.toFahrenheit()}F = ${this.toKelvin()}K"
    }
}

// Boiling point of water
let boiling = Temperature(100)
print(boiling.describe())
// 100C = 212F = 373.15K

// Freezing point
let freezing = Temperature(0)
print(freezing.describe())
// 0C = 32F = 273.15K
```
