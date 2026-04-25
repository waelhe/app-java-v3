'use client';

import React, { useState } from 'react';
import Link from 'next/link';
import {
  ShoppingBag, MapPin, Star, Clock, Flame, ChefHat, UtensilsCrossed,
  Coffee, Building, Car, Fuel, Stethoscope, Pill, Heart, Sparkles,
  Shirt, BookOpen, Trophy, Hotel, Cake, Zap, Users, Phone, ArrowRight,
  ChevronLeft, ChevronRight, Heart as HeartIcon, Award, Search
} from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';

// ═══════════════════════════════════════════════════════════════
// بيانات الدليل
// ═══════════════════════════════════════════════════════════════

// المطاعم
const restaurants = [
  { id: 'r1', name: 'مطعم الشام', nameEn: 'Al-Sham Restaurant', cuisine: 'شامي', cuisineEn: 'Levantine', rating: 4.8, reviews: 124, price: '$$', image: 'https://images.unsplash.com/photo-1544025162-d76694265947?auto=format&fit=crop&w=400&q=80', location: 'الساحة', isOpen: true, featured: true },
  { id: 'r2', name: 'بيتزا الضاحية', nameEn: 'Dahia Pizza', cuisine: 'بيتزا', cuisineEn: 'Pizza', rating: 4.5, reviews: 89, price: '$', image: 'https://images.unsplash.com/photo-1565299624946-b28f40a0ae38?auto=format&fit=crop&w=400&q=80', location: 'الشارع الرئيسي', isOpen: true },
  { id: 'r3', name: 'مشاوي البيك', nameEn: 'Al-Baik Grills', cuisine: 'مشاوي', cuisineEn: 'Grills', rating: 4.9, reviews: 156, price: '$$', image: 'https://images.unsplash.com/photo-1603360946369-dc9bb6258143?auto=format&fit=crop&w=400&q=80', location: 'الحي الغربي', isOpen: true, featured: true },
  { id: 'r4', name: 'كافيه النخيل', nameEn: 'Palm Cafe', cuisine: 'حلويات', cuisineEn: 'Desserts', rating: 4.6, reviews: 67, price: '$', image: 'https://images.unsplash.com/photo-1551024506-0bccd828d307?auto=format&fit=crop&w=400&q=80', location: 'الساحة', isOpen: true },
  { id: 'r5', name: 'سندويشات الساحة', nameEn: 'Square Sandwiches', cuisine: 'سندويشات', cuisineEn: 'Sandwiches', rating: 4.7, reviews: 98, price: '$', image: 'https://images.unsplash.com/photo-1568901346375-23c9450c58cd?auto=format&fit=crop&w=400&q=80', location: 'الساحة', isOpen: true, new: true },
  { id: 'r6', name: 'مطعم السياحة', nameEn: 'Tourism Restaurant', cuisine: 'عربي', cuisineEn: 'Arabic', rating: 4.4, reviews: 78, price: '$$', image: 'https://images.unsplash.com/photo-1517248135467-4c7edcad34c4?auto=format&fit=crop&w=400&q=80', location: 'شارع السياحة', isOpen: true },
  { id: 'r7', name: 'مأكولات البحر', nameEn: 'Seafood Kitchen', cuisine: 'بحري', cuisineEn: 'Seafood', rating: 4.9, reviews: 145, price: '$$$', image: 'https://images.unsplash.com/photo-1615141982883-c7ad0e69fd62?auto=format&fit=crop&w=400&q=80', location: 'الحي الجنوبي', isOpen: true, featured: true },
  { id: 'r8', name: 'بوفيه الشرق', nameEn: 'East Buffet', cuisine: 'بوفيه', cuisineEn: 'Buffet', rating: 4.3, reviews: 56, price: '$$', image: 'https://images.unsplash.com/photo-1555396273-367ea4eb4db5?auto=format&fit=crop&w=400&q=80', location: 'الشارع الشرقي', isOpen: true },
];

// المقاهي
const cafes = [
  { id: 'c1', name: 'كافيه النخيل', nameEn: 'Palm Cafe', type: 'كافيه', typeEn: 'Cafe', rating: 4.6, reviews: 67, image: 'https://images.unsplash.com/photo-1559925393-8be0ec4767c8?auto=format&fit=crop&w=400&q=80', location: 'الساحة', isOpen: true, hasWifi: true, hasOutdoor: true },
  { id: 'c2', name: 'كافيه الجبل', nameEn: 'Mountain Cafe', type: 'كافيه', typeEn: 'Cafe', rating: 4.5, reviews: 45, image: 'https://images.unsplash.com/photo-1554118811-1e0d58224f24?auto=format&fit=crop&w=400&q=80', location: 'الحي الغربي', isOpen: true, hasWifi: true, hasOutdoor: false },
  { id: 'c3', name: 'شاي الورود', nameEn: 'Roses Tea', type: 'شاي', typeEn: 'Tea House', rating: 4.7, reviews: 89, image: 'https://images.unsplash.com/photo-1544787219-7f47ccb76574?auto=format&fit=crop&w=400&q=80', location: 'الشارع الرئيسي', isOpen: true, hasWifi: false, hasOutdoor: true },
  { id: 'c4', name: 'مقهى الروضة', nameEn: 'Rawda Cafe', type: 'مقهى', typeEn: 'Coffee Shop', rating: 4.4, reviews: 34, image: 'https://images.unsplash.com/photo-1445116572660-236099ec97a0?auto=format&fit=crop&w=400&q=80', location: 'الحي الشرقي', isOpen: true, hasWifi: true, hasOutdoor: false },
];

// الأسواق
const markets = [
  { id: 'm1', name: 'سوبرماركت الضاحية', nameEn: 'Dahia Supermarket', type: 'سوبرماركت', typeEn: 'Supermarket', rating: 4.5, reviews: 234, image: 'https://images.unsplash.com/photo-1604719312566-8912e9227c6a?auto=format&fit=crop&w=400&q=80', location: 'الشارع الرئيسي', isOpen: true, hasDelivery: true },
  { id: 'm2', name: 'سوق الخضار', nameEn: 'Vegetable Market', type: 'خضار وفواكه', typeEn: 'Fruits & Vegetables', rating: 4.6, reviews: 156, image: 'https://images.unsplash.com/photo-1542838132-92c53300491e?auto=format&fit=crop&w=400&q=80', location: 'الساحة', isOpen: true, hasDelivery: false },
  { id: 'm3', name: 'مخبز الأمل', nameEn: 'Al-Amal Bakery', type: 'مخبز', typeEn: 'Bakery', rating: 4.8, reviews: 189, image: 'https://images.unsplash.com/photo-1509440159596-0249088772ff?auto=format&fit=crop&w=400&q=80', location: 'الحي الغربي', isOpen: true, hasDelivery: true },
  { id: 'm4', name: 'لحوم الحلال', nameEn: 'Halal Meats', type: 'جزارة', typeEn: 'Butcher', rating: 4.7, reviews: 98, image: 'https://images.unsplash.com/photo-1607623814075-e51df1bdc82f?auto=format&fit=crop&w=400&q=80', location: 'الشارع الرئيسي', isOpen: true, hasDelivery: false },
  { id: 'm5', name: 'بقالة السلام', nameEn: 'Al-Salam Grocer', type: 'بقالة', typeEn: 'Grocery', rating: 4.3, reviews: 67, image: 'https://images.unsplash.com/photo-1604719312566-8912e9227c6a?auto=format&fit=crop&w=400&q=80', location: 'الحي الشرقي', isOpen: true, hasDelivery: false },
  { id: 'm6', name: 'محل الألبان', nameEn: 'Dairy Shop', type: 'ألبان', typeEn: 'Dairy', rating: 4.5, reviews: 78, image: 'https://images.unsplash.com/photo-1628088062854-d1870b4553da?auto=format&fit=crop&w=400&q=80', location: 'الساحة', isOpen: true, hasDelivery: false },
];

// محلات تجارية
const retailShops = [
  { id: 'rs1', name: 'ملابس النخبة', nameEn: 'Elite Clothing', type: 'ألبسة', typeEn: 'Clothing', rating: 4.4, reviews: 56, image: 'https://images.unsplash.com/photo-1441986300917-64674bd600d8?auto=format&fit=crop&w=400&q=80', location: 'الشارع الرئيسي' },
  { id: 'rs2', name: 'خرداوات الشامل', nameEn: 'Shamel Hardware', type: 'خرداوات', typeEn: 'Hardware', rating: 4.5, reviews: 89, image: 'https://images.unsplash.com/photo-1581092921461-eab62e97a780?auto=format&fit=crop&w=400&q=80', location: 'الساحة' },
  { id: 'rs3', name: 'محمصة الضاحية', nameEn: 'Dahia Roastery', type: 'محمصة', typeEn: 'Roastery', rating: 4.7, reviews: 123, image: 'https://images.unsplash.com/photo-1514432324607-a09d9b4aefdd?auto=format&fit=crop&w=400&q=80', location: 'الشارع الرئيسي' },
  { id: 'rs4', name: 'مركز الاتصالات', nameEn: 'Telecom Center', type: 'إلكترونيات', typeEn: 'Electronics', rating: 4.3, reviews: 45, image: 'https://images.unsplash.com/photo-1511707171634-5f897ff02aa9?auto=format&fit=crop&w=400&q=80', location: 'الساحة' },
  { id: 'rs5', name: 'مستلزمات الأطفال', nameEn: 'Baby Supplies', type: 'أطفال', typeEn: 'Baby', rating: 4.6, reviews: 67, image: 'https://images.unsplash.com/photo-1515488042361-ee00e0ddd4e4?auto=format&fit=crop&w=400&q=80', location: 'الحي الغربي' },
  { id: 'rs6', name: 'مكتبة المعرفة', nameEn: 'Knowledge Bookshop', type: 'مكتبة', typeEn: 'Bookshop', rating: 4.8, reviews: 34, image: 'https://images.unsplash.com/photo-1526243741027-444d633d7365?auto=format&fit=crop&w=400&q=80', location: 'الشارع الرئيسي' },
];

// محطات بنزين
const gasStations = [
  { id: 'g1', name: 'محطة الفيول', nameEn: 'Fuel Station', type: 'فيول', typeEn: 'Fuel', rating: 4.3, reviews: 89, image: 'https://images.unsplash.com/photo-1591149975854-248a7a4e80e8?auto=format&fit=crop&w=400&q=80', location: 'مدخل الضاحية', hasService: true },
  { id: 'g2', name: 'محطة الوفاء', nameEn: 'Al-Wafaa Station', type: 'بنزين', typeEn: 'Gasoline', rating: 4.4, reviews: 67, image: 'https://images.unsplash.com/photo-1559304787-e8b7a67d1d99?auto=format&fit=crop&w=400&q=80', location: 'الشارع الرئيسي', hasService: true },
  { id: 'g3', name: 'محطة النور', nameEn: 'Al-Noor Station', type: 'ديزل', typeEn: 'Diesel', rating: 4.2, reviews: 45, image: 'https://images.unsplash.com/photo-1591149975854-248a7a4e80e8?auto=format&fit=crop&w=400&q=80', location: 'الحي الصناعي', hasService: false },
];

// خدمات السيارات
const carServices = [
  { id: 'cs1', name: 'مغسلة السيارات', nameEn: 'Car Wash', type: 'غسيل', typeEn: 'Wash', rating: 4.5, reviews: 78, image: 'https://images.unsplash.com/photo-1520340356584-f9917d1eea6f?auto=format&fit=crop&w=400&q=80', location: 'الشارع الرئيسي', price: '500-1500' },
  { id: 'cs2', name: 'مركز الصيانة', nameEn: 'Maintenance Center', type: 'ميكانيك', typeEn: 'Mechanic', rating: 4.6, reviews: 56, image: 'https://images.unsplash.com/photo-1486262715619-67b85e0b08d3?auto=format&fit=crop&w=400&q=80', location: 'الحي الصناعي', price: 'حسب الإصلاح' },
  { id: 'cs3', name: 'إطارات الشامل', nameEn: 'Shamel Tires', type: 'إطارات', typeEn: 'Tires', rating: 4.4, reviews: 34, image: 'https://images.unsplash.com/photo-1489824904134-891ab64532f1?auto=format&fit=crop&w=400&q=80', location: 'مدخل الضاحية', price: 'حسب الحجم' },
  { id: 'cs4', name: 'كهرباء سيارات', nameEn: 'Car Electric', type: 'كهرباء', typeEn: 'Electric', rating: 4.7, reviews: 45, image: 'https://images.unsplash.com/photo-1619642751034-765dfdf7c58e?auto=format&fit=crop&w=400&q=80', location: 'الشارع الرئيسي', price: 'حسب الإصلاح' },
];

// الأطباء
const doctors = [
  { id: 'd1', name: 'د. أحمد محمد', nameEn: 'Dr. Ahmed Mohammad', specialty: 'طبيب عام', specialtyEn: 'General Practitioner', rating: 4.8, reviews: 156, image: 'https://images.unsplash.com/photo-1612349317150-e413f6a5b16d?auto=format&fit=crop&w=400&q=80', location: 'عيادة الساحة', price: '5000', isOpen: true },
  { id: 'd2', name: 'د. فاطمة علي', nameEn: 'Dr. Fatima Ali', specialty: 'طب أطفال', specialtyEn: 'Pediatrician', rating: 4.9, reviews: 189, image: 'https://images.unsplash.com/photo-1559839734-2b71ea197ec2?auto=format&fit=crop&w=400&q=80', location: 'عيادة الأطفال', price: '7000', isOpen: true, featured: true },
  { id: 'd3', name: 'د. محمود خالد', nameEn: 'Dr. Mahmoud Khaled', specialty: 'طبيب أسنان', specialtyEn: 'Dentist', rating: 4.7, reviews: 98, image: 'https://images.unsplash.com/photo-1606811841689-23dfddce3e95?auto=format&fit=crop&w=400&q=80', location: 'عيادة الأسنان', price: '10000', isOpen: true },
  { id: 'd4', name: 'د. سارة أحمد', nameEn: 'Dr. Sara Ahmed', specialty: 'طبيب نسائية', specialtyEn: 'Gynecologist', rating: 4.9, reviews: 234, image: 'https://images.unsplash.com/photo-1594824476967-48c8b964273f?auto=format&fit=crop&w=400&q=80', location: 'عيادة النساء', price: '10000', isOpen: true, featured: true },
  { id: 'd5', name: 'د. يوسف حسن', nameEn: 'Dr. Youssef Hassan', specialty: 'جراح عام', specialtyEn: 'General Surgeon', rating: 4.8, reviews: 167, image: 'https://images.unsplash.com/photo-1537368910025-700350fe46c7?auto=format&fit=crop&w=400&q=80', location: 'مستشفى الشفاء', price: '15000', isOpen: true },
  { id: 'd6', name: 'د. نورة سعيد', nameEn: 'Dr. Noura Saeed', specialty: 'طبيب جلدية', specialtyEn: 'Dermatologist', rating: 4.6, reviews: 78, image: 'https://images.unsplash.com/photo-1614608682850-e0d6ed316d47?auto=format&fit=crop&w=400&q=80', location: 'عيادة الجلدية', price: '8000', isOpen: true },
];

// الصيدليات
const pharmacies = [
  { id: 'p1', name: 'صيدلية الشفاء', nameEn: 'Al-Shifa Pharmacy', rating: 4.7, reviews: 234, image: 'https://images.unsplash.com/photo-1576602976047-174e57a47881?auto=format&fit=crop&w=400&q=80', location: 'الشارع الرئيسي', isOpen: true, is24h: true },
  { id: 'p2', name: 'صيدلية النور', nameEn: 'Al-Noor Pharmacy', rating: 4.6, reviews: 156, image: 'https://images.unsplash.com/photo-1631549916768-4119b2e5f926?auto=format&fit=crop&w=400&q=80', location: 'الساحة', isOpen: true, is24h: false },
  { id: 'p3', name: 'صيدلية الأمل', nameEn: 'Al-Amal Pharmacy', rating: 4.8, reviews: 189, image: 'https://images.unsplash.com/photo-1587854692152-cbe660dbde88?auto=format&fit=crop&w=400&q=80', location: 'الحي الغربي', isOpen: true, is24h: true },
  { id: 'p4', name: 'صيدلية الضاحية', nameEn: 'Dahia Pharmacy', rating: 4.5, reviews: 98, image: 'https://images.unsplash.com/photo-1576602976047-174e57a47881?auto=format&fit=crop&w=400&q=80', location: 'الضاحية', isOpen: true, is24h: false },
];

// التجميل
const beauty = [
  { id: 'b1', name: 'صالون جمال', nameEn: 'Jamal Salon', type: 'حلاقة رجالي', typeEn: "Men's Barbershop", rating: 4.6, reviews: 156, image: 'https://images.unsplash.com/photo-1503951914875-452162b0f3f1?auto=format&fit=crop&w=400&q=80', location: 'الشارع الرئيسي', price: '3000-8000' },
  { id: 'b2', name: 'صالون الورد', nameEn: 'Al-Ward Salon', type: 'صالون نسائي', typeEn: "Women's Salon", rating: 4.8, reviews: 234, image: 'https://images.unsplash.com/photo-1560066984-138dadb4c035?auto=format&fit=crop&w=400&q=80', location: 'الحي الغربي', price: '5000-15000' },
  { id: 'b3', name: 'مركز التجميل', nameEn: 'Beauty Center', type: 'عناية بشرة', typeEn: 'Skincare', rating: 4.7, reviews: 89, image: 'https://images.unsplash.com/photo-1487412947147-5cebf100ffc2?auto=format&fit=crop&w=400&q=80', location: 'الساحة', price: '10000-30000' },
  { id: 'b4', name: 'سبا الراحة', nameEn: 'Relax Spa', type: 'سبا ومساج', typeEn: 'Spa & Massage', rating: 4.9, reviews: 167, image: 'https://images.unsplash.com/photo-1544161515-4ab6ce6db874?auto=format&fit=crop&w=400&q=80', location: 'شارع الفنادق', price: '15000-50000' },
];

// الغسيل
const laundry = [
  { id: 'l1', name: 'مغسلة النظافة', nameEn: 'Clean Laundry', type: 'غسيل وكي', typeEn: 'Wash & Iron', rating: 4.5, reviews: 78, image: 'https://images.unsplash.com/photo-1604335399105-a0c585fd81a1?auto=format&fit=crop&w=400&q=80', location: 'الشارع الرئيسي', price: 'حسب القطعة' },
  { id: 'l2', name: 'تنظيف جاف ممتاز', nameEn: 'Excellent Dry Clean', type: 'تنظيف جاف', typeEn: 'Dry Cleaning', rating: 4.7, reviews: 56, image: 'https://images.unsplash.com/photo-1582735689369-4fe89db7114c?auto=format&fit=crop&w=400&q=80', location: 'الساحة', price: 'حسب القطعة' },
  { id: 'l3', name: 'غسيل السجاد', nameEn: 'Carpet Washing', type: 'سجاد ومفروشات', typeEn: 'Carpets & Furniture', rating: 4.4, reviews: 34, image: 'https://images.unsplash.com/photo-1558618666-fcd25c85cd64?auto=format&fit=crop&w=400&q=80', location: 'الحي الصناعي', price: 'حسب المساحة' },
];

// التعليم
const education = [
  { id: 'e1', name: 'مدرسة الأمل', nameEn: 'Al-Amal School', type: 'ابتدائي', typeEn: 'Primary', rating: 4.6, reviews: 89, image: 'https://images.unsplash.com/photo-1580582932707-520aed937b7b?auto=format&fit=crop&w=400&q=80', location: 'الحي الغربي' },
  { id: 'e2', name: 'مدرسة النور', nameEn: 'Al-Noor School', type: 'إعدادي', typeEn: 'Middle', rating: 4.5, reviews: 67, image: 'https://images.unsplash.com/photo-1509062522246-3755977927d7?auto=format&fit=crop&w=400&q=80', location: 'الشارع الرئيسي' },
  { id: 'e3', name: 'ثانوية القدس', nameEn: 'Al-Quds High', type: 'ثانوي', typeEn: 'High School', rating: 4.7, reviews: 123, image: 'https://images.unsplash.com/photo-1523050854058-8df90110c9f1?auto=format&fit=crop&w=400&q=80', location: 'الحي الشرقي' },
  { id: 'e4', name: 'معهد اللغات', nameEn: 'Language Institute', type: 'معهد', typeEn: 'Institute', rating: 4.8, reviews: 56, image: 'https://images.unsplash.com/photo-1503676260728-1c00da094a0b?auto=format&fit=crop&w=400&q=80', location: 'الساحة' },
];

// الرياضة
const sports = [
  { id: 's1', name: 'نادي اللياقة', nameEn: 'Fitness Club', type: 'جيم', typeEn: 'Gym', rating: 4.6, reviews: 234, image: 'https://images.unsplash.com/photo-1534438327276-14e5300c3a48?auto=format&fit=crop&w=400&q=80', location: 'الشارع الرئيسي', price: '25000/شهر' },
  { id: 's2', name: 'مسبح السلام', nameEn: 'Al-Salam Pool', type: 'سباحة', typeEn: 'Swimming', rating: 4.5, reviews: 156, image: 'https://images.unsplash.com/photo-1576013551627-0cc20b96c2a7?auto=format&fit=crop&w=400&q=80', location: 'شارع الفنادق', price: '10000/زيارة' },
  { id: 's3', name: 'ملعب كرة القدم', nameEn: 'Football Field', type: 'كرة قدم', typeEn: 'Football', rating: 4.4, reviews: 89, image: 'https://images.unsplash.com/photo-1574629810360-7efbbe195018?auto=format&fit=crop&w=400&q=80', location: 'الحي الرياضي', price: '5000/ساعة' },
];

// السياحة والفنادق
const tourism = [
  { id: 't1', name: 'فندق النخيل', nameEn: 'Palm Hotel', type: 'فندق', typeEn: 'Hotel', rating: 4.7, reviews: 345, image: 'https://images.unsplash.com/photo-1566073771259-6a8506099945?auto=format&fit=crop&w=400&q=80', location: 'شارع الفنادق', price: '$50-100' },
  { id: 't2', name: 'شقق السياحة', nameEn: 'Tourism Apartments', type: 'شقق فندقية', typeEn: 'Hotel Apartments', rating: 4.5, reviews: 167, image: 'https://images.unsplash.com/photo-1555834831-6c2e81f05c2b?auto=format&fit=crop&w=400&q=80', location: 'الحي الغربي', price: '$40-80' },
  { id: 't3', name: 'منتجع الهدوء', nameEn: 'Peace Resort', type: 'منتجع', typeEn: 'Resort', rating: 4.8, reviews: 234, image: 'https://images.unsplash.com/photo-1571003123894-1f0594d2b5d9?auto=format&fit=crop&w=400&q=80', location: 'التلال', price: '$100-200' },
];

// خدمات المناسبات
const eventServices = [
  { id: 'ev1', name: 'حلويات السعادة', nameEn: 'Happiness Sweets', type: 'حلويات', typeEn: 'Sweets', rating: 4.8, reviews: 289, image: 'https://images.unsplash.com/photo-1558301211-0d8c8ddee6ec?auto=format&fit=crop&w=400&q=80', location: 'الشارع الرئيسي' },
  { id: 'ev2', name: 'زهور الجنة', nameEn: 'Paradise Flowers', type: 'زهور', typeEn: 'Flowers', rating: 4.7, reviews: 156, image: 'https://images.unsplash.com/photo-1487530811176-3780de880c2d?auto=format&fit=crop&w=400&q=80', location: 'الساحة' },
  { id: 'ev3', name: 'فرن المناسبات', nameEn: 'Occasions Bakery', type: 'أفران', typeEn: 'Bakery', rating: 4.6, reviews: 178, image: 'https://images.unsplash.com/photo-1509440159596-0249088772ff?auto=format&fit=crop&w=400&q=80', location: 'الحي الغربي' },
  { id: 'ev4', name: 'هدايا النخبة', nameEn: 'Elite Gifts', type: 'هدايا', typeEn: 'Gifts', rating: 4.5, reviews: 89, image: 'https://images.unsplash.com/photo-1549465220-1a8b9238cd48?auto=format&fit=crop&w=400&q=80', location: 'الشارع الرئيسي' },
];

// ═══════════════════════════════════════════════════════════════
// المكونات الفرعية
// ═══════════════════════════════════════════════════════════════

interface ItemCardProps {
  item: {
    id: string;
    name: string;
    nameEn: string;
    rating: number;
    reviews: number;
    image: string;
    location: string;
    featured?: boolean;
    new?: boolean;
    isOpen?: boolean;
    [key: string]: unknown;
  };
  isArabic: boolean;
}

function ItemCard({ item, isArabic }: ItemCardProps) {
  const [isFavorite, setIsFavorite] = useState(false);

  return (
    <div className="flex-shrink-0 w-[200px] sm:w-[240px] bg-white rounded-xl border border-gray-100 overflow-hidden hover:shadow-lg transition-all cursor-pointer group">
      {/* Image */}
      <div className="relative aspect-[4/3] overflow-hidden">
        <img src={item.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
        <div className="absolute inset-0 bg-gradient-to-t from-black/40 to-transparent" />

        {/* Favorite */}
        <button
          onClick={(e) => { e.stopPropagation(); setIsFavorite(!isFavorite); }}
          className="absolute top-2 right-2 p-1.5"
        >
          <HeartIcon className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
        </button>

        {/* Status */}
        {item.isOpen !== undefined && (
          <span className={`absolute top-2 left-2 px-2 py-0.5 text-[10px] font-bold rounded-full ${item.isOpen ? 'bg-green-500 text-white' : 'bg-gray-500 text-white'}`}>
            {item.isOpen ? (isArabic ? 'مفتوح' : 'Open') : (isArabic ? 'مغلق' : 'Closed')}
          </span>
        )}

        {/* Featured/New */}
        {(item.featured || item.new) && (
          <span className={`absolute bottom-2 right-2 px-2 py-0.5 text-[10px] font-bold rounded-full ${item.featured ? 'bg-amber-500 text-white' : 'bg-emerald-500 text-white'}`}>
            {item.featured ? (isArabic ? 'مميز' : 'Featured') : (isArabic ? 'جديد' : 'New')}
          </span>
        )}
      </div>

      {/* Info */}
      <div className="p-3">
        <h3 className="font-bold text-gray-900 text-sm mb-1 line-clamp-1">
          {isArabic ? item.name : item.nameEn}
        </h3>
        <div className="flex items-center gap-2 text-xs text-gray-500 mb-1">
          <div className="flex items-center gap-1">
            <Star className="w-3.5 h-3.5 text-amber-400 fill-amber-400" />
            <span className="font-bold">{item.rating}</span>
            <span className="text-gray-400">({item.reviews})</span>
          </div>
        </div>
        <div className="flex items-center gap-1 text-xs text-gray-500">
          <MapPin className="w-3 h-3" />
          <span className="line-clamp-1">{item.location}</span>
        </div>
      </div>
    </div>
  );
}

interface SectionProps {
  id: string;
  title: string;
  titleEn: string;
  icon: React.ElementType;
  iconColor: string;
  items: ItemCardProps['item'][];
  isArabic: boolean;
  filters?: { id: string; name: string; nameEn: string }[];
  getFilterValue?: (item: ItemCardProps['item'], filterId: string) => boolean;
}

function Section({ id, title, titleEn, icon: Icon, iconColor, items, isArabic, filters, getFilterValue }: SectionProps) {
  const [activeFilter, setActiveFilter] = useState('all');
  const scrollRef = React.useRef<HTMLDivElement>(null);

  const filteredItems = filters && getFilterValue
    ? items.filter(item => activeFilter === 'all' || getFilterValue(item, activeFilter))
    : items;

  const scroll = (direction: 'left' | 'right') => {
    if (scrollRef.current) {
      scrollRef.current.scrollBy({
        left: direction === 'left' ? -300 : 300,
        behavior: 'smooth'
      });
    }
  };

  return (
    <section id={id} className="scroll-mt-32 py-6 border-b border-gray-100 last:border-b-0">
      {/* Header */}
      <div className="flex items-center gap-3 mb-4">
        <div className={`p-2 ${iconColor} rounded-xl`}>
          <Icon className="w-5 h-5 text-white" />
        </div>
        <div className="flex-1">
          <h2 className="text-lg font-black text-gray-900">
            {isArabic ? title : titleEn}
          </h2>
          <p className="text-xs text-gray-500">
            {filteredItems.length} {isArabic ? 'عنصر' : 'items'}
          </p>
        </div>
      </div>

      {/* Filters */}
      {filters && (
        <div className="flex gap-2 mb-4 overflow-x-auto pb-2 scrollbar-hide">
          {filters.map((filter) => (
            <button
              key={filter.id}
              onClick={() => setActiveFilter(filter.id)}
              className={`px-4 py-2 rounded-xl text-xs font-bold transition-colors ${
                activeFilter === filter.id
                  ? 'bg-gray-900 text-white'
                  : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
              }`}
            >
              {isArabic ? filter.name : filter.nameEn}
            </button>
          ))}
        </div>
      )}

      {/* Cards */}
      <div className="relative">
        <button
          onClick={() => scroll('left')}
          className="absolute left-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center hover:bg-gray-50 transition-colors"
        >
          <ChevronRight className="w-4 h-4" />
        </button>
        <button
          onClick={() => scroll('right')}
          className="absolute right-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center hover:bg-gray-50 transition-colors"
        >
          <ChevronLeft className="w-4 h-4" />
        </button>

        <div
          ref={scrollRef}
          className="flex gap-3 overflow-x-auto pb-2 scrollbar-hide"
          style={{ scrollbarWidth: 'none', msOverflowStyle: 'none' }}
        >
          {filteredItems.map((item) => (
            <ItemCard key={item.id} item={item} isArabic={isArabic} />
          ))}
        </div>
      </div>
    </section>
  );
}

// ═══════════════════════════════════════════════════════════════
// الصفحة الرئيسية
// ═══════════════════════════════════════════════════════════════

export default function DirectoryPage() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const [searchQuery, setSearchQuery] = useState('');

  const sections = [
    { id: 'restaurants', name: 'مطاعم ومقاهي', nameEn: 'Restaurants & Cafes' },
    { id: 'markets', name: 'أسواق ومتاجر', nameEn: 'Markets & Shops' },
    { id: 'cars', name: 'بنزين وسيارات', nameEn: 'Gas & Cars' },
    { id: 'medical', name: 'أطباء وصيدليات', nameEn: 'Doctors & Pharmacies' },
    { id: 'beauty', name: 'تجميل وعناية', nameEn: 'Beauty & Care' },
    { id: 'laundry', name: 'مغاسل وتنظيف', nameEn: 'Laundry & Cleaning' },
    { id: 'education', name: 'تعليم ومدارس', nameEn: 'Education' },
    { id: 'sports', name: 'مراكز رياضية', nameEn: 'Sports Centers' },
    { id: 'tourism', name: 'سياحة وفنادق', nameEn: 'Tourism & Hotels' },
    { id: 'events', name: 'خدمات المناسبات', nameEn: 'Event Services' },
  ];

  const scrollToSection = (id: string) => {
    document.getElementById(id)?.scrollIntoView({ behavior: 'smooth' });
  };

  return (
    <main className="min-h-screen bg-gradient-to-b from-blue-50 to-white" dir={isArabic ? 'rtl' : 'ltr'}>
      {/* Header */}
      <header className="sticky top-0 z-50 bg-white/95 backdrop-blur-md border-b border-blue-100 shadow-sm">
        <div className="max-w-7xl mx-auto px-4 py-3">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <Link href="/" className="p-2 hover:bg-gray-100 rounded-xl transition-colors">
                <ArrowRight className="w-5 h-5 text-gray-600" />
              </Link>
              <div className="p-2 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-xl shadow-lg">
                <MapPin className="w-6 h-6 text-white" />
              </div>
              <div>
                <h1 className="text-lg font-black text-gray-900">
                  {isArabic ? 'الدليل المحلي' : 'Local Directory'}
                </h1>
                <p className="text-xs text-gray-500">
                  {isArabic ? 'كل ما تحتاجه في منطقتك' : 'Everything you need in your area'}
                </p>
              </div>
            </div>
          </div>

          {/* Search */}
          <div className="mt-3 relative">
            <Search className="absolute right-3 top-1/2 -translate-y-1/2 w-5 h-5 text-gray-400" />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder={isArabic ? 'ابحث عن محل، مطعم، خدمة...' : 'Search for shop, restaurant, service...'}
              className="w-full pr-10 pl-4 py-3 bg-gray-100 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>
        </div>
      </header>

      {/* Quick Jump */}
      <div className="sticky top-[130px] z-40 bg-white/95 backdrop-blur-md border-b border-gray-100 shadow-sm">
        <div className="max-w-7xl mx-auto px-4 py-2">
          <div className="flex gap-2 overflow-x-auto pb-1 scrollbar-hide">
            {sections.map((section) => (
              <button
                key={section.id}
                onClick={() => scrollToSection(section.id)}
                className="px-4 py-2 bg-gray-100 hover:bg-blue-100 text-gray-700 hover:text-blue-700 text-xs font-bold rounded-xl whitespace-nowrap transition-colors"
              >
                {isArabic ? section.name : section.nameEn}
              </button>
            ))}
          </div>
        </div>
      </div>

      <div className="max-w-7xl mx-auto px-4">
        {/* المطاعم والمقاهي */}
        <Section
          id="restaurants"
          title="🍽️ مطاعم ومقاهي"
          titleEn="🍽️ Restaurants & Cafes"
          icon={UtensilsCrossed}
          iconColor="bg-orange-500"
          items={[...restaurants, ...cafes]}
          isArabic={isArabic}
          filters={[
            { id: 'all', name: 'الكل', nameEn: 'All' },
            { id: 'restaurant', name: 'مطاعم', nameEn: 'Restaurants' },
            { id: 'cafe', name: 'مقاهي', nameEn: 'Cafes' },
          ]}
          getFilterValue={(item, filterId) => {
            if (filterId === 'restaurant') return !item.type?.includes('كافيه') && !item.type?.includes('شاي') && !item.type?.includes('مقهى');
            if (filterId === 'cafe') return item.type?.includes('كافيه') || item.type?.includes('شاي') || item.type?.includes('مقهى') || item.cuisine === 'حلويات';
            return true;
          }}
        />

        {/* الأسواق والمتاجر */}
        <Section
          id="markets"
          title="🛒 أسواق ومتاجر"
          titleEn="🛒 Markets & Shops"
          icon={ShoppingBag}
          iconColor="bg-emerald-500"
          items={[...markets, ...retailShops]}
          isArabic={isArabic}
          filters={[
            { id: 'all', name: 'الكل', nameEn: 'All' },
            { id: 'food', name: 'غذائية', nameEn: 'Food' },
            { id: 'retail', name: 'تجارية', nameEn: 'Retail' },
          ]}
          getFilterValue={(item, filterId) => {
            if (filterId === 'food') return ['سوبرماركت', 'خضار وفواكه', 'مخبز', 'جزارة', 'بقالة', 'ألبان'].includes(item.type as string);
            if (filterId === 'retail') return ['ألبسة', 'خرداوات', 'محمصة', 'إلكترونيات', 'أطفال', 'مكتبة'].includes(item.type as string);
            return true;
          }}
        />

        {/* بنزين وسيارات */}
        <Section
          id="cars"
          title="⛽ بنزين وخدمات سيارات"
          titleEn="⛽ Gas & Car Services"
          icon={Car}
          iconColor="bg-green-500"
          items={[...gasStations, ...carServices]}
          isArabic={isArabic}
          filters={[
            { id: 'all', name: 'الكل', nameEn: 'All' },
            { id: 'gas', name: 'محطات بنزين', nameEn: 'Gas Stations' },
            { id: 'service', name: 'خدمات سيارات', nameEn: 'Car Services' },
          ]}
          getFilterValue={(item, filterId) => {
            if (filterId === 'gas') return ['فيول', 'بنزين', 'ديزل'].includes(item.type as string);
            if (filterId === 'service') return ['غسيل', 'ميكانيك', 'إطارات', 'كهرباء'].includes(item.type as string);
            return true;
          }}
        />

        {/* أطباء وصيدليات */}
        <Section
          id="medical"
          title="🏥 أطباء وصيدليات"
          titleEn="🏥 Doctors & Pharmacies"
          icon={Stethoscope}
          iconColor="bg-rose-500"
          items={[...doctors, ...pharmacies]}
          isArabic={isArabic}
          filters={[
            { id: 'all', name: 'الكل', nameEn: 'All' },
            { id: 'doctor', name: 'أطباء', nameEn: 'Doctors' },
            { id: 'pharmacy', name: 'صيدليات', nameEn: 'Pharmacies' },
          ]}
          getFilterValue={(item, filterId) => {
            if (filterId === 'doctor') return !!item.specialty;
            if (filterId === 'pharmacy') return !item.specialty && item.rating;
            return true;
          }}
        />

        {/* تجميل وعناية */}
        <Section
          id="beauty"
          title="✨ تجميل وعناية"
          titleEn="✨ Beauty & Care"
          icon={Sparkles}
          iconColor="bg-pink-500"
          items={beauty}
          isArabic={isArabic}
          filters={[
            { id: 'all', name: 'الكل', nameEn: 'All' },
            { id: 'men', name: 'رجالي', nameEn: 'Men' },
            { id: 'women', name: 'نسائي', nameEn: 'Women' },
            { id: 'spa', name: 'سبا', nameEn: 'Spa' },
          ]}
          getFilterValue={(item, filterId) => {
            if (filterId === 'men') return item.type?.includes('رجالي');
            if (filterId === 'women') return item.type?.includes('نسائي');
            if (filterId === 'spa') return item.type?.includes('سبا');
            return true;
          }}
        />

        {/* مغاسل وتنظيف */}
        <Section
          id="laundry"
          title="🧺 مغاسل وتنظيف"
          titleEn="🧺 Laundry & Cleaning"
          icon={Shirt}
          iconColor="bg-cyan-500"
          items={laundry}
          isArabic={isArabic}
        />

        {/* تعليم ومدارس */}
        <Section
          id="education"
          title="📚 تعليم ومدارس"
          titleEn="📚 Education"
          icon={BookOpen}
          iconColor="bg-indigo-500"
          items={education}
          isArabic={isArabic}
          filters={[
            { id: 'all', name: 'الكل', nameEn: 'All' },
            { id: 'primary', name: 'ابتدائي', nameEn: 'Primary' },
            { id: 'middle', name: 'إعدادي', nameEn: 'Middle' },
            { id: 'high', name: 'ثانوي', nameEn: 'High School' },
          ]}
          getFilterValue={(item, filterId) => {
            if (filterId === 'primary') return item.type === 'ابتدائي';
            if (filterId === 'middle') return item.type === 'إعدادي';
            if (filterId === 'high') return item.type === 'ثانوي';
            return true;
          }}
        />

        {/* مراكز رياضية */}
        <Section
          id="sports"
          title="🏆 مراكز رياضية"
          titleEn="🏆 Sports Centers"
          icon={Trophy}
          iconColor="bg-green-500"
          items={sports}
          isArabic={isArabic}
          filters={[
            { id: 'all', name: 'الكل', nameEn: 'All' },
            { id: 'gym', name: 'جيم', nameEn: 'Gym' },
            { id: 'pool', name: 'سباحة', nameEn: 'Swimming' },
            { id: 'football', name: 'كرة قدم', nameEn: 'Football' },
          ]}
          getFilterValue={(item, filterId) => {
            if (filterId === 'gym') return item.type === 'جيم';
            if (filterId === 'pool') return item.type === 'سباحة';
            if (filterId === 'football') return item.type === 'كرة قدم';
            return true;
          }}
        />

        {/* سياحة وفنادق */}
        <Section
          id="tourism"
          title="📍 سياحة وفنادق"
          titleEn="📍 Tourism & Hotels"
          icon={Hotel}
          iconColor="bg-cyan-500"
          items={tourism}
          isArabic={isArabic}
        />

        {/* خدمات المناسبات */}
        <Section
          id="events"
          title="🎉 خدمات المناسبات"
          titleEn="🎉 Event Services"
          icon={Cake}
          iconColor="bg-rose-500"
          items={eventServices}
          isArabic={isArabic}
          filters={[
            { id: 'all', name: 'الكل', nameEn: 'All' },
            { id: 'sweets', name: 'حلويات', nameEn: 'Sweets' },
            { id: 'flowers', name: 'زهور', nameEn: 'Flowers' },
            { id: 'bakery', name: 'أفران', nameEn: 'Bakery' },
            { id: 'gifts', name: 'هدايا', nameEn: 'Gifts' },
          ]}
          getFilterValue={(item, filterId) => {
            if (filterId === 'sweets') return item.type === 'حلويات';
            if (filterId === 'flowers') return item.type === 'زهور';
            if (filterId === 'bakery') return item.type === 'أفران';
            if (filterId === 'gifts') return item.type === 'هدايا';
            return true;
          }}
        />
      </div>

      {/* Footer */}
      <div className="h-20" />
    </main>
  );
}
