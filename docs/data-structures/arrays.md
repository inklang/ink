---
sidebar_position: 1
---

# Arrays

Arrays store ordered collections of values that can be accessed by index.

## Creating Arrays

```ink
let numbers = [1, 2, 3, 4, 5]
let mixed = [1, "hello", true]
let empty = []
```

## Accessing Elements

Array elements are accessed by their index, starting at 0:

```ink
let fruits = ["apple", "banana", "cherry"]

print(fruits[0])  // apple
print(fruits[1])  // banana
print(fruits[2])  // cherry
```

## Modifying Elements

```ink
let numbers = [1, 2, 3]

numbers[0] = 10
print(numbers[0])  // 10
```

## Array Methods

### Getting Size

```ink
let arr = [1, 2, 3]
print(arr.size())  // 3
```

### Adding Elements

```ink
let arr = [1, 2, 3]
arr.push(4)
print(arr.size())  // 4
```

### Getting Elements

```ink
let arr = ["a", "b", "c"]
let first = arr.get(0)    // "a"
let last = arr.get(2)    // "c"
let missing = arr.get(10)  // null (out of bounds)
```

### Setting Elements

```ink
let arr = [1, 2, 3]
arr.set(1, 20)
print(arr.get(1))  // 20
```

## Iterating Over Arrays

### Using For Loop

```ink
let fruits = ["apple", "banana", "cherry"]

for fruit in fruits {
    print(fruit)
}
// Output: apple, banana, cherry
```

### Using Index

```ink
let items = ["first", "second", "third"]

for i in 0..items.size() {
    print("${i}: ${items.get(i)}")
}
// Output: 0: first, 1: second, 2: third
```

## Practical Example: Todo List

```ink
let todos = []

fn addTodo(todo) {
    todos.push(todo)
}

fn showTodos() {
    print("Your todos:")
    for i in 0..todos.size() {
        print("${i + 1}. ${todos.get(i)}")
    }
}

addTodo("Learn ink")
addTodo("Build a project")
showTodos()
```

## Next Steps

Learn about [Maps](/docs/data-structures/maps) for key-value storage.
