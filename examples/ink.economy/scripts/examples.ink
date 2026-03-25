// ink.economy usage examples

// Check balance
let balance = eco_balance("Steve")
print("Steve's balance: ${balance}")

// Give coins
let newBal = eco_give("Steve", 100)
print("Gave Steve 100 coins. New balance: ${newBal}")

// Take coins (clamped to 0)
let after = eco_take("Steve", 1000)
print("Took up to 1000 from Steve. Final balance: ${after}")

// Set balance
eco_set("Steve", 500)
print("Set Steve's balance to 500")

// Transfer
let ok = eco_transfer("Steve", "Treasury", 50)
if !ok {
    print("Transfer failed -- insufficient funds!")
}

// Top 5 richest
let top = eco_top(5)
print("Top 5 richest players:")
print(top)
