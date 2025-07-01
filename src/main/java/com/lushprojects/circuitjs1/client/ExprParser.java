package com.lushprojects.circuitjs1.client;

public class ExprParser {
    String text;
    String token;
    int pos;
    int tlen;
    String err;

    void getToken() {
        while (pos < tlen && text.charAt(pos) == ' ')
            pos++;
        if (pos == tlen) {
            token = "";
            return;
        }
        int i = pos;
        int c = text.charAt(i);
        if ((c >= '0' && c <= '9') || c == '.') {
            for (i = pos; i != tlen; i++) {
                if (text.charAt(i) == 'e' || text.charAt(i) == 'E') {
                    i++;
                    if (i < tlen && (text.charAt(i) == '+' || text.charAt(i) == '-'))
                        i++;
                }
                if (!((text.charAt(i) >= '0' && text.charAt(i) <= '9') ||
                        text.charAt(i) == '.'))
                    break;
            }
        } else if (c >= 'a' && c <= 'z') {
            for (i = pos; i != tlen; i++) {
                if (!(text.charAt(i) >= 'a' && text.charAt(i) <= 'z'))
                    break;
            }
        } else {
            i++;
            if (i < tlen) {
                // ||, &&, <<, >>, ==
                if (text.charAt(i) == c && (c == '|' || c == '&' || c == '<' || c == '>' || c == '='))
                    i++;
                    // <=, >=
                else if ((c == '<' || c == '>' || c == '!') && text.charAt(i) == '=')
                    i++;
            }
        }
        token = text.substring(pos, i);
        pos = i;
    }

    boolean skip(String s) {
        if (token.compareTo(s) != 0)
            return false;
        getToken();
        return true;
    }

    void setError(String s) {
        if (err == null)
            err = s;
    }

    void skipOrError(String s) {
        if (!skip(s)) {
            setError("expected " + s + ", got " + token);
        }
    }

    public Expr parseExpression() {
        if (token.length() == 0)
            return new Expr(Expr.E_VAL, 0.);
        Expr e = parse();
        if (token.length() > 0)
            setError("unexpected token: " + token);
        return e;
    }

    Expr parse() {
        Expr e = parseOr();
        Expr e2, e3;
        if (skip("?")) {
            e2 = parseOr();
            skipOrError(":");
            e3 = parse();
            Expr ret = new Expr(e, e2, Expr.E_TERNARY);
            ret.children.add(e3);
            return ret;
        }
        return e;
    }

    Expr parseOr() {
        Expr e = parseAnd();
        while (skip("||")) {
            e = new Expr(e, parseAnd(), Expr.E_OR);
        }
        return e;
    }

    Expr parseAnd() {
        Expr e = parseEquals();
        while (skip("&&")) {
            e = new Expr(e, parseEquals(), Expr.E_AND);
        }
        return e;
    }

    Expr parseEquals() {
        Expr e = parseCompare();
        if (skip("=="))
            return new Expr(e, parseCompare(), Expr.E_EQUALS);
        return e;
    }

    Expr parseCompare() {
        Expr e = parseAdd();
        if (skip("<="))
            return new Expr(e, parseAdd(), Expr.E_LEQ);
        if (skip(">="))
            return new Expr(e, parseAdd(), Expr.E_GEQ);
        if (skip("!="))
            return new Expr(e, parseAdd(), Expr.E_NEQ);
        if (skip("<"))
            return new Expr(e, parseAdd(), Expr.E_LESS);
        if (skip(">"))
            return new Expr(e, parseAdd(), Expr.E_GREATER);
        return e;
    }

    Expr parseAdd() {
        Expr e = parseMult();
        while (true) {
            if (skip("+"))
                e = new Expr(e, parseMult(), Expr.E_ADD);
            else if (skip("-"))
                e = new Expr(e, parseMult(), Expr.E_SUB);
            else
                break;
        }
        return e;
    }

    Expr parseMult() {
        Expr e = parseUminus();
        while (true) {
            if (skip("*"))
                e = new Expr(e, parseUminus(), Expr.E_MUL);
            else if (skip("/"))
                e = new Expr(e, parseUminus(), Expr.E_DIV);
            else
                break;
        }
        return e;
    }

    Expr parseUminus() {
        skip("+");
        if (skip("!"))
            return new Expr(parseUminus(), null, Expr.E_NOT);
        if (skip("-"))
            return new Expr(parseUminus(), null, Expr.E_UMINUS);
        return parsePow();
    }

    Expr parsePow() {
        Expr e = parseTerm();
        while (true) {
            if (skip("^"))
                e = new Expr(e, parseTerm(), Expr.E_POW);
            else
                break;
        }
        return e;
    }

    Expr parseFunc(int t) {
        skipOrError("(");
        Expr e = parse();
        skipOrError(")");
        return new Expr(e, null, t);
    }

    Expr parseFuncMulti(int t, int minArgs, int maxArgs) {
        int args = 1;
        skipOrError("(");
        Expr e1 = parse();
        Expr e = new Expr(e1, null, t);
        while (skip(",")) {
            Expr enext = parse();
            e.children.add(enext);
            args++;
        }
        skipOrError(")");
        if (args < minArgs || args > maxArgs)
            setError("bad number of function args: " + args);
        return e;
    }

    Expr parseTerm() {
        if (skip("(")) {
            Expr e = parse();
            skipOrError(")");
            return e;
        }
        if (skip("t"))
            return new Expr(Expr.E_T);
        if (token.length() == 1) {
            char c = token.charAt(0);
            if (c >= 'a' && c <= 'i') {
                getToken();
                return new Expr(Expr.E_A + (c - 'a'));
            }
        }
        if (token.startsWith("last") && token.length() == 5) {
            char c = token.charAt(4);
            if (c >= 'a' && c <= 'i') {
                getToken();
                return new Expr(Expr.E_LASTA + (c - 'a'));
            }
        }
        if (token.endsWith("dt") && token.startsWith("d") && token.length() == 4) {
            char c = token.charAt(1);
            if (c >= 'a' && c <= 'i') {
                getToken();
                return new Expr(Expr.E_DADT + (c - 'a'));
            }
        }
        if (skip("lastoutput"))
            return new Expr(Expr.E_LASTOUTPUT);
        if (skip("timestep"))
            return new Expr(Expr.E_TIMESTEP);
        if (skip("pi"))
            return new Expr(Expr.E_VAL, 3.14159265358979323846);
//	if (skip("e"))
//	    return new Expr(Expr.E_VAL, 2.7182818284590452354);
        if (skip("sin"))
            return parseFunc(Expr.E_SIN);
        if (skip("cos"))
            return parseFunc(Expr.E_COS);
        if (skip("asin"))
            return parseFunc(Expr.E_ASIN);
        if (skip("acos"))
            return parseFunc(Expr.E_ACOS);
        if (skip("atan"))
            return parseFunc(Expr.E_ATAN);
        if (skip("sinh"))
            return parseFunc(Expr.E_SINH);
        if (skip("cosh"))
            return parseFunc(Expr.E_COSH);
        if (skip("tanh"))
            return parseFunc(Expr.E_TANH);
        if (skip("abs"))
            return parseFunc(Expr.E_ABS);
        if (skip("exp"))
            return parseFunc(Expr.E_EXP);
        if (skip("log"))
            return parseFunc(Expr.E_LOG);
        if (skip("sqrt"))
            return parseFunc(Expr.E_SQRT);
        if (skip("tan"))
            return parseFunc(Expr.E_TAN);
        if (skip("tri"))
            return parseFunc(Expr.E_TRIANGLE);
        if (skip("saw"))
            return parseFunc(Expr.E_SAWTOOTH);
        if (skip("floor"))
            return parseFunc(Expr.E_FLOOR);
        if (skip("ceil"))
            return parseFunc(Expr.E_CEIL);
        if (skip("min"))
            return parseFuncMulti(Expr.E_MIN, 2, 1000);
        if (skip("max"))
            return parseFuncMulti(Expr.E_MAX, 2, 1000);
        if (skip("pwl"))
            return parseFuncMulti(Expr.E_PWL, 2, 1000);
        if (skip("mod"))
            return parseFuncMulti(Expr.E_MOD, 2, 2);
        if (skip("step"))
            return parseFuncMulti(Expr.E_STEP, 1, 2);
        if (skip("select"))
            return parseFuncMulti(Expr.E_SELECT, 3, 3);
        if (skip("clamp"))
            return parseFuncMulti(Expr.E_CLAMP, 3, 3);
        if (skip("pwr"))
            return parseFuncMulti(Expr.E_PWR, 2, 2);
        if (skip("pwrs"))
            return parseFuncMulti(Expr.E_PWRS, 2, 2);
        try {
            Expr e = new Expr(Expr.E_VAL, Double.valueOf(token).doubleValue());
            getToken();
            return e;
        } catch (Exception e) {
            if (token.length() == 0)
                setError("unexpected end of input");
            else
                setError("unrecognized token: " + token);
            return new Expr(Expr.E_VAL, 0);
        }
    }

    public ExprParser(String s) {
        text = s.toLowerCase();
        tlen = text.length();
        pos = 0;
        err = null;
        getToken();
    }

    public String gotError() {
        return err;
    }
}
