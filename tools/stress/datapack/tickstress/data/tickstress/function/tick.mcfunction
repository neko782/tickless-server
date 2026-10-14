execute as @e[type=!player] run scoreboard players add @s ts_ticks 1
execute as @e[type=zombie] at @s run tp @s ~ ~ ~ ~7 ~
execute as @e[type=item] run data merge entity @s {Age:0s}
execute store result score #count ts_ticks if entity @e
effect give @e[type=cow] minecraft:speed 1 1 true
