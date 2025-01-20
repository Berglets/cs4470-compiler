

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;


public class compiler {
    public static void main(String[] args) throws Exception {

        String filepath;
        if(args.length == 2)
            filepath = args[1];
        else
            filepath = args[0];

       // throw new Exception("FILEPATH----" + filepath + "FILEPATH----" + );

        //String currentWorkingDirectory = System.getProperty("user.dir");
        Path fullPath = Paths.get(filepath);

        String jpl_code = "";
        try {
            jpl_code = Files.readString(fullPath);

            // Print the content
            //System.out.println(jpl_code);
        } catch (IOException e) {
            e.printStackTrace();
	    System.out.println("Compilation failed");
	    return;
        }
	//throw new Exception("FILE CONTENTS: " + jpl_code);


        var output = Lex(jpl_code);

        if(output == null)
            System.out.println("Compilation failed");
        else {
            for(var token : output) {
                System.out.println(token.toString());
            }
            System.out.println("Compilation succeeded");
        }

    }

    public static boolean hasOnlyValidCharacters(String jpl_code) {
        // Check if the byte value of the character is valid for JPL
        for(int i = 0; i < jpl_code.length(); i++) {
            char c = jpl_code.charAt(i);
            if((c < 32 || c > 126) && c != 10)
                return false;
        }
        return true;
    }


    // returns end position
    // -1 if not an int
    // DON'T THROW
    public static Tuple<Integer, Token> isInt(String str, int start_pos) {
        int curr_pos = start_pos;
        int max_pos = str.length();

        // invalid if it's not a digit to start. Ends at end digit
        if(!Character.isDigit(str.charAt(start_pos)))
            return new Tuple<>(-1, null);

        while(curr_pos < max_pos) {
            if(Character.isDigit(str.charAt(curr_pos)))
                curr_pos++;
            else
                break;
        }

        int val = Integer.parseInt(str.substring(start_pos, curr_pos));
        return new Tuple<>(curr_pos, new IntToken(val));
    }

    public static Tuple<Integer, Token> isFloat(String str, int start_pos) {
        int curr_pos = start_pos;
        int max_pos = str.length();
        boolean has_digit = false;
        boolean has_dot = false;

        // go through digits before dot
        while(curr_pos < max_pos) {
            if(Character.isDigit(str.charAt(curr_pos)))
                has_digit = true;
            else if(str.charAt(curr_pos) == '.' && !has_dot)
                has_dot = true;
            else
                break;
            curr_pos++;
        }

        if(!has_digit || !has_dot)
            return new Tuple<>(-1, null);

        String literal_val = str.substring(start_pos, curr_pos);
        float val = Float.parseFloat(literal_val);
        return new Tuple<>(curr_pos, new FloatToken(val, literal_val));
    }


    // TODO check end of string it could jsut cut off
    public static Tuple<Integer, Token> isString(String str, int start_pos) {
        if(str.charAt(start_pos) != '\"')
            return new Tuple<>(-1, null);

        int curr_pos = start_pos+1;
        int max_pos = str.length();

        while(curr_pos < max_pos) {
            if(str.charAt(curr_pos) != '\"')
                curr_pos++;
            else
                break;
        }

        return new Tuple<>(curr_pos+1, new StringToken(str.substring(start_pos, curr_pos+1)));
    }

    public static Tuple<Integer, Token> isIdentifier(String str, int start_pos) {
        if(!Character.isLetter(str.charAt(start_pos)))
            return new Tuple<>(-1, null);

        int curr_pos = start_pos;
        int max_pos = str.length();

        while(curr_pos < max_pos) {
            if(Character.isLetterOrDigit(str.charAt(curr_pos)) || str.charAt(curr_pos) == '_')
                curr_pos++;
            else
                break;
        }

        String identifier = str.substring(start_pos, curr_pos);
        // if this string is a keyword or punctuation, not valid
        var tup1 = isKeyword(identifier, 0);
        var tup2 = isPunctuation(identifier, 0);
        if(tup1.x == identifier.length() || tup2.x == identifier.length())
            return new Tuple<>(-1, null);

        return new Tuple<>(curr_pos, new Identifier(identifier));
    }

    public static int isSingleLineComment(String str, int start_pos) {

        if(!startsWithSubstring(str, start_pos, "//"))
            return -1;

        int curr_pos = start_pos;
        int max_pos = str.length();

        while(curr_pos < max_pos) {
            if(str.charAt(curr_pos) != '\n')
                curr_pos++;
            else
                break;
        }

        return curr_pos + 1;
    }

    public static int isMultiLineComment(String str, int start_pos) {
        if(!startsWithSubstring(str, start_pos, "/*"))
            return -1;

        int curr_pos = start_pos;
        int max_pos = str.length() - 1;

        while(curr_pos < max_pos) {
            if(str.charAt(curr_pos) == '*' && str.charAt(curr_pos+1) == '/')
                break;
            else
                curr_pos++;
        }

        return curr_pos+2;
    }

    public static Tuple<Integer, Token> isOperator(String str, int start_pos) {
        String[] operators = {"+", "-", "/", "*", "%", "&&", "||", "==", "!=", ">=", "<="};

        for(var op : operators) {
            if(startsWithSubstring(str, start_pos, op))
                return new Tuple<>(start_pos+op.length(), new Operator(op));
        }

        // these have a longer operator gotta check separately
        if(str.charAt(start_pos) == '!')
            return new Tuple<>(start_pos+1, new Operator("!"));
        if(str.charAt(start_pos) == '<')
            return new Tuple<>(start_pos+1, new Operator("<"));
        if(str.charAt(start_pos) == '>')
            return new Tuple<>(start_pos+1, new Operator(">"));

        return new Tuple<>(-1, null);
    }

    public static Tuple<Integer, Token> isPunctuation(String str, int start_pos) {

        if(str.charAt(start_pos) == '[')
            return new Tuple<>(start_pos+1, new Punctuation("LSQUARE", '['));
        if(str.charAt(start_pos) == ']')
            return new Tuple<>(start_pos+1, new Punctuation("RSQUARE", ']'));
        if(str.charAt(start_pos) == '(')
            return new Tuple<>(start_pos+1, new Punctuation("LPAREN", '('));
        if(str.charAt(start_pos) == ')')
            return new Tuple<>(start_pos+1, new Punctuation("RPAREN", ')'));
        if(str.charAt(start_pos) == '{')
            return new Tuple<>(start_pos+1, new Punctuation("LCURLY", '{'));
        if(str.charAt(start_pos) == '}')
            return new Tuple<>(start_pos+1, new Punctuation("RCURLY", '}'));
        if(str.charAt(start_pos) == ',')
            return new Tuple<>(start_pos+1, new Punctuation("COMMA", ','));
        if(str.charAt(start_pos) == ':')
            return new Tuple<>(start_pos+1, new Punctuation("COLON", ':'));
        if(str.charAt(start_pos) == '.')
            return new Tuple<>(start_pos+1, new Punctuation("DOT", '.'));
        if(str.charAt(start_pos) == '=')
            return new Tuple<>(start_pos+1, new Punctuation("EQUALS", '='));

        return new Tuple<>(-1, null);
    }

    public static Tuple<Integer, Token> isKeyword(String str, int start_pos) {

    String[] keywords = {"array", "assert", "bool", "else", "false", "float", "fn", "if", "image", "int", "let",
            "print", "read", "return", "show", "struct", "sum", "then", "time", "to", "true", "void", "write"};

    for(String keyword : keywords) {
        if(startsWithSubstring(str, start_pos, keyword))
            return new Tuple<>(start_pos+keyword.length(), new Keyword(keyword));
    }

    return new Tuple<>(-1, null);
}

    public static boolean startsWithSubstring(String str, int start_pos, String substring) {
        int str_pos = start_pos;
        int sub_pos = 0;

        while(sub_pos < substring.length()) {
            if(str_pos == str.length() || str.charAt(str_pos) != substring.charAt(sub_pos))
                return false;
            str_pos++;
            sub_pos++;
        }
        return true;
    }

    // returns null if error
    public static List<Token> Lex(String jpl_code) {
        if(!hasOnlyValidCharacters(jpl_code)) {
            return null;
        }

        List<Token> tokens = new ArrayList<>();

        int curr_pos = 0;
        int max_pos = jpl_code.length();

        while(curr_pos < max_pos) {
            // check for white space
            if(jpl_code.charAt(curr_pos) == ' ') {
                curr_pos++;
                continue;
            }

            if(startsWithSubstring(jpl_code, curr_pos, "\\\n")) {
                curr_pos += 2;
                continue;
            }

            // check for newline
            if(jpl_code.charAt(curr_pos) == '\n') {
                if(tokens.size() == 0 || !tokens.get(tokens.size() - 1).toString().equals("NEWLINE")) // last token was not a newline
                    tokens.add(new Newline());
                curr_pos++;
                continue;
            }

            // check for comments
            int end_pos = isSingleLineComment(jpl_code, curr_pos);
            if(end_pos != -1) {
                curr_pos = end_pos;
                if(tokens.size() == 0 || !tokens.get(tokens.size() - 1).toString().equals("NEWLINE")) // last token was not a newline
                    tokens.add(new Newline());
                continue;
            }
            end_pos = isMultiLineComment(jpl_code, curr_pos);
            if(end_pos != -1) {
                curr_pos = end_pos;
                continue;
            }

            // check for float
            var tup = isFloat(jpl_code, curr_pos);
            if(tup.x != -1) {
                tokens.add(tup.y);
                curr_pos = tup.x;
                continue;
            }

            // check for int
            tup = isInt(jpl_code, curr_pos);
            if(tup.x != -1) {
                tokens.add(tup.y);
                curr_pos = tup.x;
                continue;
            }

            // check for string
            tup = isString(jpl_code, curr_pos);
            if(tup.x != -1) {
                tokens.add(tup.y);
                curr_pos = tup.x;
                continue;
            }

            // check for operator
            tup = isOperator(jpl_code, curr_pos);
            if(tup.x != -1) {
                tokens.add(tup.y);
                curr_pos = tup.x;
                continue;
            }

            // check for variable
            tup = isIdentifier(jpl_code, curr_pos);
            if(tup.x != -1) {
                tokens.add(tup.y);
                curr_pos = tup.x;
                continue;
            }

            // check for keyword
            tup = isKeyword(jpl_code, curr_pos);
            if(tup.x != -1) {
                tokens.add(tup.y);
                curr_pos = tup.x;
                continue;
            }

            // check for punctuation
            tup = isPunctuation(jpl_code, curr_pos);
            if(tup.x != -1) {
                tokens.add(tup.y);
                curr_pos = tup.x;
                continue;
            }

            return null;
        }

        if(tokens.size() == 0 || !tokens.get(tokens.size() - 1).toString().equals("NEWLINE")) // last token was not a newline
            tokens.add(new Newline());
        tokens.add(new EndOfFile());
        return tokens;
    }
}

class Token {
    // start and end char points
    int start;
    int end;
}

class Keyword extends Token {
    String keyword;

    public Keyword(String keyword) {
        this.keyword = keyword.toLowerCase();
    }

    @Override
    public String toString() {
        return keyword.toUpperCase() + " \'" + keyword + "\'";
    }
}

/**
 *  14 operators: + - / * % && || ! > < != == >= <=       (= not an op)
 */
class Operator extends Token {
    String operator;

    public Operator(String operator) {
        this.operator = operator;
    }

    @Override
    public String toString() {
        return "OP \'" + operator + "\'";
    }
}

/**
 * 9 punctuation:  LCURLY LPAREN LSQUARE RCURLY RPAREN RSQUARE DOT COMMA COLON
 */
class Punctuation extends Token {
    String name;
    char character;

    public Punctuation(String name, char character) {
        this.name = name.toUpperCase();
        this.character = character;
    }

    @Override
    public String toString() {
        return name + " \'" + character + "\'";
    }
}

class IntToken extends Token {
    int val;

    public IntToken(int val) {
        this.val = val;
    }

    @Override
    public String toString() {
        return "INTVAL \'" + val + "\'";
    }
}

class FloatToken extends Token {
    float val;
    String literal_val;

    public FloatToken(float val, String literal_val) {
        this.val = val;
        this.literal_val = literal_val;
    }

    @Override
    public String toString() {
        return "FLOATVAL \'" + literal_val + "\'";
    }
}

class Identifier extends Token {
    String identifier;

    public Identifier(String identifier) {
        this.identifier = identifier;
    }

    @Override
    public String toString() {
        return "VARIABLE \'" + identifier + "\'";
    }
}

class Newline extends Token {
    @Override
    public String toString() {
        return "NEWLINE";
    }
}

class EndOfFile extends Token {
    @Override
    public String toString() {
        return "END_OF_FILE";
    }
}

class StringToken extends Token {
    String text;

    public StringToken(String text) {
        this.text = text;
    }

    @Override
    public String toString() {
        return "STRING \'" + text + "\'";
    }
}

class Tuple<X, Y> {
    public final X x;
    public final Y y;
    public Tuple(X x, Y y) {
        this.x = x;
        this.y = y;
    }
}
