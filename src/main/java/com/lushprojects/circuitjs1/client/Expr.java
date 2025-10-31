package com.lushprojects.circuitjs1.client;

import java.util.Vector;

public class Expr {
    public Expr(Expr e1, Expr e2, int v) {
        children = new Vector<Expr>();
        children.add(e1);
        if (e2 != null)
            children.add(e2);
        type = v;
    }

    public Expr(int v, double vv) {
        type = v;
        value = vv;
    }

    public Expr(int v) {
        type = v;
    }

    public double eval(ExprState es) {
        Expr left = null;
        Expr right = null;
        if (children != null && !children.isEmpty()) {
            left = children.firstElement();
            if (children.size() == 2)
                right = children.lastElement();
        }
        switch (type) {
            case E_ADD:
                return left.eval(es) + right.eval(es);
            case E_SUB:
                return left.eval(es) - right.eval(es);
            case E_MUL:
                return left.eval(es) * right.eval(es);
            case E_DIV:
                return left.eval(es) / right.eval(es);
            case E_POW:
                return Math.pow(left.eval(es), right.eval(es));
            case E_OR:
                return (left.eval(es) != 0 || right.eval(es) != 0) ? 1 : 0;
            case E_AND:
                return (left.eval(es) != 0 && right.eval(es) != 0) ? 1 : 0;
            case E_EQUALS:
                return (left.eval(es) == right.eval(es)) ? 1 : 0;
            case E_NEQ:
                return (left.eval(es) != right.eval(es)) ? 1 : 0;
            case E_LEQ:
                return (left.eval(es) <= right.eval(es)) ? 1 : 0;
            case E_GEQ:
                return (left.eval(es) >= right.eval(es)) ? 1 : 0;
            case E_LESS:
                return (left.eval(es) < right.eval(es)) ? 1 : 0;
            case E_GREATER:
                return (left.eval(es) > right.eval(es)) ? 1 : 0;
            case E_TERNARY:
                return children.get(left.eval(es) != 0 ? 1 : 2).eval(es);
            case E_UMINUS:
                return -left.eval(es);
            case E_NOT:
                return left.eval(es) == 0 ? 1 : 0;
            case E_VAL:
                return value;
            case E_T:
                return es.t;
            case E_SIN:
                return Math.sin(left.eval(es));
            case E_COS:
                return Math.cos(left.eval(es));
            case E_ABS:
                return Math.abs(left.eval(es));
            case E_EXP:
                return Math.exp(left.eval(es));
            case E_LOG:
                return Math.log(left.eval(es));
            case E_SQRT:
                return Math.sqrt(left.eval(es));
            case E_TAN:
                return Math.tan(left.eval(es));
            case E_ASIN:
                return Math.asin(left.eval(es));
            case E_ACOS:
                return Math.acos(left.eval(es));
            case E_ATAN:
                return Math.atan(left.eval(es));
            case E_SINH:
                return Math.sinh(left.eval(es));
            case E_COSH:
                return Math.cosh(left.eval(es));
            case E_TANH:
                return Math.tanh(left.eval(es));
            case E_FLOOR:
                return Math.floor(left.eval(es));
            case E_CEIL:
                return Math.ceil(left.eval(es));
            case E_MIN: {
                int i;
                double x = left.eval(es);
                for (i = 1; i < children.size(); i++)
                    x = Math.min(x, children.get(i).eval(es));
                return x;
            }
            case E_MAX: {
                int i;
                double x = left.eval(es);
                for (i = 1; i < children.size(); i++)
                    x = Math.max(x, children.get(i).eval(es));
                return x;
            }
            case E_CLAMP:
                return Math.min(Math.max(left.eval(es), children.get(1).eval(es)), children.get(2).eval(es));
            case E_STEP: {
                double x = left.eval(es);
                if (right == null)
                    return (x < 0) ? 0 : 1;
                return (x > right.eval(es)) ? 0 : (x < 0) ? 0 : 1;
            }
            case E_SELECT: {
                double x = left.eval(es);
                return children.get(x > 0 ? 2 : 1).eval(es);
            }
            case E_TRIANGLE: {
                double x = posmod(left.eval(es), Math.PI * 2) / Math.PI;
                return (x < 1) ? -1 + x * 2 : 3 - x * 2;
            }
            case E_SAWTOOTH: {
                double x = posmod(left.eval(es), Math.PI * 2) / Math.PI;
                return x - 1;
            }
            case E_MOD:
                return left.eval(es) % right.eval(es);
            case E_PWL:
                return pwl(es, children);
            case E_PWR:
                return Math.pow(Math.abs(left.eval(es)), right.eval(es));
            case E_PWRS: {
                double x = left.eval(es);
                if (x < 0)
                    return -Math.pow(-x, right.eval(es));
                return Math.pow(x, right.eval(es));
            }
            case E_LASTOUTPUT:
                return es.lastOutput;
            case E_TIMESTEP:
                return CirSim.theSim.simulator().timeStep;
            default:
                if (type >= E_LASTA)
                    return es.lastValues[type - E_LASTA];
                if (type >= E_DADT)
                    return (es.values[type - E_DADT] - es.lastValues[type - E_DADT]) / CirSim.theSim.simulator().timeStep;
                if (type >= E_A)
                    return es.values[type - E_A];
                CirSim.console("unknown\n");
        }
        return 0;
    }

    static double pwl(ExprState es, Vector<Expr> args) {
        double x = args.get(0).eval(es);
        double x0 = args.get(1).eval(es);
        double y0 = args.get(2).eval(es);
        if (x < x0)
            return y0;
        double x1 = args.get(3).eval(es);
        double y1 = args.get(4).eval(es);
        int i = 5;
        while (true) {
            if (x < x1)
                return y0 + (x - x0) * (y1 - y0) / (x1 - x0);
            if (i + 1 >= args.size())
                break;
            x0 = x1;
            y0 = y1;
            x1 = args.get(i).eval(es);
            y1 = args.get(i + 1).eval(es);
            i += 2;
        }
        return y1;
    }

    static double posmod(double x, double y) {
        x %= y;
        return (x >= 0) ? x : x + y;
    }

    Vector<Expr> children;
    double value;
    int type;
    static final int E_ADD = 1;
    static final int E_SUB = 2;
    static final int E_T = 3;
    static final int E_VAL = 6;
    static final int E_MUL = 7;
    static final int E_DIV = 8;
    static final int E_POW = 9;
    static final int E_UMINUS = 10;
    static final int E_SIN = 11;
    static final int E_COS = 12;
    static final int E_ABS = 13;
    static final int E_EXP = 14;
    static final int E_LOG = 15;
    static final int E_SQRT = 16;
    static final int E_TAN = 17;
    static final int E_R = 18;
    static final int E_MAX = 19;
    static final int E_MIN = 20;
    static final int E_CLAMP = 21;
    static final int E_PWL = 22;
    static final int E_TRIANGLE = 23;
    static final int E_SAWTOOTH = 24;
    static final int E_MOD = 25;
    static final int E_STEP = 26;
    static final int E_SELECT = 27;
    static final int E_PWR = 28;
    static final int E_PWRS = 29;
    static final int E_LASTOUTPUT = 30;
    static final int E_TIMESTEP = 31;
    static final int E_TERNARY = 32;
    static final int E_OR = 33;
    static final int E_AND = 34;
    static final int E_EQUALS = 35;
    static final int E_LEQ = 36;
    static final int E_GEQ = 37;
    static final int E_LESS = 38;
    static final int E_GREATER = 39;
    static final int E_NEQ = 40;
    static final int E_NOT = 41;
    static final int E_FLOOR = 42;
    static final int E_CEIL = 43;
    static final int E_ASIN = 44;
    static final int E_ACOS = 45;
    static final int E_ATAN = 46;
    static final int E_SINH = 47;
    static final int E_COSH = 48;
    static final int E_TANH = 49;
    static final int E_A = 50;
    static final int E_DADT = E_A + 10; // must be E_A+10
    static final int E_LASTA = E_DADT + 10; // should be at end and equal to E_DADT+10
};

;
