package me.gibson.chatgames.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Environment(EnvType.CLIENT)
public class ChatGameSolver implements ClientModInitializer {
    private static final Pattern MATH_PATTERN = Pattern.compile("\\d+[+\\-*/]\\d+");
    private static final Pattern TEXT_PATTERN = Pattern.compile("First to type the word: ([a-zA-Z0-9]+)");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("Pick a random number: (\\d+) to (\\d+)");
    private static final Random RANDOM = new Random();
    private final Set<Integer> guessedNumbers = new HashSet<>();
    private final Queue<MessageToSend> messageQueue = new LinkedList<>();
    private boolean gameActive = false;
    private boolean messageSent = false;
    private boolean enabled = true;

    private static KeyBinding toggleKey;

    @Override
    public void onInitializeClient() {
        System.out.println("ChatGameSolver initializing...");

        // Initialize the toggle keybinding
        toggleKey = new KeyBinding("key.chatgamesolver.toggle", GLFW.GLFW_KEY_G, "key.categories.misc");
        net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(toggleKey);

        System.out.println("Keybinding registered.");

        // Register listener for incoming chat messages
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            System.out.println("Chat event triggered.");
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && message != null) {
                String strippedMessage = stripColorCodes(message.getString());
                System.out.println("Received message: " + strippedMessage);

                if (enabled) {
                    processChatMessage(client, strippedMessage);
                }
            }
        });

        System.out.println("Chat message listener registered.");

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (toggleKey.wasPressed()) {
                enabled = !enabled;
                System.out.println("Chat Game Solver toggled. Current state: " + (enabled ? "enabled" : "disabled"));
                sendTitleMessage(client, "Chat Game Solver", "is now " + (enabled ? "enabled" : "disabled"));
            }

            if (!messageQueue.isEmpty() && gameActive) {
                long currentTime = System.currentTimeMillis();
                MessageToSend nextMessage = messageQueue.peek();

                if (nextMessage != null && currentTime >= nextMessage.getSendTime()) {
                    int numberToGuess = Integer.parseInt(nextMessage.getMessage());
                    if (!guessedNumbers.contains(numberToGuess)) {
                        sendChatMessage(client, nextMessage.getMessage());
                        guessedNumbers.add(numberToGuess);
                        System.out.println("Sent guess: " + numberToGuess);
                        messageQueue.poll();
                    } else {
                        System.out.println("Skipping already guessed number: " + numberToGuess);
                        messageQueue.poll();
                    }
                }
            } else if (!gameActive && !messageQueue.isEmpty()) {
                messageQueue.clear(); // Clear the queue if the game has ended
            }
        });

        System.out.println("Tick event registered.");
    }

    private void processChatMessage(MinecraftClient client, String message) {
        System.out.println("Processing message: " + message);

        // Check if a new game has started
        if (message.contains("Chat Games") && !gameActive) {
            System.out.println("Game started.");
            guessedNumbers.clear();
            gameActive = true;
            messageSent = false;
        }

        // Check if the game has ended
        if (message.contains("found the answer") || message.contains("The correct answer is")) {
            System.out.println("Game ended.");
            gameActive = false;
            messageSent = false;
        }

        // Monitor guesses from other players during the game
        if (gameActive && message.matches(".*\\d+.*")) { // Matches a number in the message
            try {
                Matcher matcher = Pattern.compile("\\b\\d+\\b").matcher(message); // Extract numbers
                while (matcher.find()) {
                    int guessedNumber = Integer.parseInt(matcher.group());
                    if (!guessedNumbers.contains(guessedNumber)) {
                        guessedNumbers.add(guessedNumber);
                        System.out.println("Added guessed number from another player: " + guessedNumber);
                    }
                }
            } catch (NumberFormatException e) {
                System.out.println("Failed to parse guessed number: " + message);
            }
        }

        // Handle the game logic
        if (gameActive && !messageSent) {
            Matcher numberMatcher = NUMBER_PATTERN.matcher(message);
            if (numberMatcher.find()) {
                // Random number guessing game
                int start = Integer.parseInt(numberMatcher.group(1));
                int end = Integer.parseInt(numberMatcher.group(2));
                System.out.println("Random number range: " + start + " to " + end);

                long cumulativeDelay = 0; // Track cumulative delay
                List<Integer> randomNumbers = new ArrayList<>();
                for (int i = start; i <= end; i++) {
                    randomNumbers.add(i);
                }
                Collections.shuffle(randomNumbers); // Randomize the order

                for (int number : randomNumbers) {
                    if (!guessedNumbers.contains(number)) {
                        int delay = 1200 + RANDOM.nextInt(1500); // Random delay between 1.2 - 2.7 seconds
                        cumulativeDelay += delay;
                        messageQueue.add(new MessageToSend(String.valueOf(number), System.currentTimeMillis() + cumulativeDelay));
                        System.out.println("Queued random guess: " + number + " with cumulative delay: " + cumulativeDelay + "ms");
                    }
                }
            } else {
                // Handle other games (e.g., math or text challenges)
                String solution = solveChatGame(message);
                if (solution != null) {
                    int delay = 1200 + RANDOM.nextInt(1500); // Random delay between 1.2 - 2.7 seconds
                    System.out.println("Solution found: " + solution + ". Queuing to send after delay: " + delay + "ms");
                    messageQueue.add(new MessageToSend(solution, System.currentTimeMillis() + delay));
                    messageSent = true; // Ensure message is queued only once
                }
            }
        }
    }


    private String solveChatGame(String message) {
        Matcher mathMatcher = MATH_PATTERN.matcher(message);
        if (mathMatcher.find()) {
            String equation = mathMatcher.group();
            System.out.println("Math equation detected: " + equation);
            return solveMathEquation(equation);
        }

        Matcher textMatcher = TEXT_PATTERN.matcher(message);
        if (textMatcher.find()) {
            String word = textMatcher.group(1);
            System.out.println("Text challenge detected: " + word);
            return word;
        }

        return null;
    }

    private String solveMathEquation(String equation) {
        try {
            equation = equation.replaceAll("\\s", "");
            double result = evaluateExpression(equation);
            System.out.println("Evaluating equation: " + equation + " = " + result);
            return String.valueOf((int) result);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    private double evaluateExpression(String expression) {
        return new Object() {
            int pos = -1, ch;

            void nextChar() {
                ch = (++pos < expression.length()) ? expression.charAt(pos) : -1;
            }

            boolean eat(int charToEat) {
                while (ch == ' ') nextChar();
                if (ch == charToEat) {
                    nextChar();
                    return true;
                }
                return false;
            }

            double parse() {
                nextChar();
                double x = parseExpression();
                if (pos < expression.length()) throw new RuntimeException("Unexpected: " + (char) ch);
                return x;
            }

            double parseExpression() {
                double x = parseTerm();
                for (;;) {
                    if (eat('+')) x += parseTerm();
                    else if (eat('-')) x -= parseTerm();
                    else return x;
                }
            }

            double parseTerm() {
                double x = parseFactor();
                for (;;) {
                    if (eat('*')) x *= parseFactor();
                    else if (eat('/')) x /= parseFactor();
                    else return x;
                }
            }

            double parseFactor() {
                if (eat('+')) return parseFactor();
                if (eat('-')) return -parseFactor();

                double x;
                int startPos = this.pos;
                if (eat('(')) {
                    x = parseExpression();
                    eat(')');
                } else if ((ch >= '0' && ch <= '9') || ch == '.') {
                    while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
                    x = Double.parseDouble(expression.substring(startPos, this.pos));
                } else {
                    throw new RuntimeException("Unexpected: " + (char) ch);
                }

                return x;
            }
        }.parse();
    }

    private void sendChatMessage(MinecraftClient client, String message) {
        ClientPlayerEntity player = client.player;
        if (player != null) {
            System.out.println("Sending chat message: " + message);
            player.networkHandler.sendChatMessage(message);
            guessedNumbers.add(Integer.parseInt(message));
        }
    }

    private void sendTitleMessage(MinecraftClient client, String title, String subtitle) {
        if (client != null && client.player != null) {
            System.out.println("Sending title: " + title + ", subtitle: " + subtitle);
            client.inGameHud.setTitle(Text.of(title));
            client.inGameHud.setSubtitle(Text.of(subtitle));
            client.inGameHud.setTitleTicks(10, 70, 20);
        }
    }

    private String stripColorCodes(String input) {
        String result = input.replaceAll("\\u00a7[0-9a-fk-or]", "");
        System.out.println("Stripped color codes: " + result);
        return result;
    }

    private static class MessageToSend {
        private final String message;
        private final long sendTime;

        public MessageToSend(String message, long sendTime) {
            this.message = message;
            this.sendTime = sendTime;
        }

        public String getMessage() {
            return message;
        }

        public long getSendTime() {
            return sendTime;
        }
    }
}
