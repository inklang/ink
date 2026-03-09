---
sidebar_position: 4
---

# Control Flow

Control the flow of execution in your Ain programs.

## If Statements

Execute code conditionally:

```ain
let score = 85

if score >= 90 {
    print("A")
} else if score >= 80 {
    print("B")
} else if score >= 70 {
    print("C")
} else {
    print("F")
}
```

## While Loops

Repeat code while a condition is true:

```ain
let count = 0

while count < 5 {
    print(count)
    count = count + 1
}
// Output: 0, 1, 2, 3, 4
```

## For Loops

Iterate over ranges or collections:

### Range Iteration

```ain
for i in 0..5 {
    print(i)
}
// Output: 0, 1, 2, 3, 4
```

### Array Iteration

```ain
let fruits = ["apple", "banana", "cherry"]

for fruit in fruits {
    print(fruit)
}
// Output: apple, banana, cherry
```

### Index-Based Iteration

```ain
let items = [10, 20, 30]

for i in 0..len(items) {
    print("Index " + i + ": " + items[i])
}
```

## Break and Continue

Control loop execution:

```ain
// Break - exit the loop early
let i = 0
while true {
    if i >= 5 {
        break
    }
    print(i)
    i = i + 1
}

// Continue - skip to next iteration
for i in 0..10 {
    if i % 2 == 0 {
        continue
    }
    print(i)  // Only prints odd numbers
}
```

## Nested Control Flow

Combine control structures:

```ain
for i in 0..3 {
    for j in 0..3 {
        if i == j {
            print("Diagonal: " + i)
        }
    }
}
```

## Next Steps

Now learn about [Functions](/docs/functions/defining) to organize your code.
