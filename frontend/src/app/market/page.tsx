'use client';

import React, { useState } from 'react';
import Link from 'next/link';
import {
  ShoppingBag, MapPin, Star, Clock, Heart, ChevronLeft, ChevronRight,
  Building, Bed, Bath, Maximize, Package, Briefcase, Scale, Banknote,
  Phone, ArrowRight, Search, Filter, Grid, List, Flame, Sparkles,
  Tag, User, MessageCircle, Eye, Calendar, DollarSign, Percent,
  Home, Landmark, Building2, Store, Warehouse, Smartphone, Sofa, Car, Laptop, Home as HomeIcon
} from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { catalogService, type Listing } from '@/lib/api';

// ═══════════════════════════════════════════════════════════════
// بيانات السوق
// ═══════════════════════════════════════════════════════════════

// الوظائف
const jobs = [
  { id: 'j1', title: 'مطلوب محاسب', titleEn: 'Accountant Needed', company: 'شركة الأمل', companyEn: 'Al-Amal Co.', type: 'دوام كامل', typeEn: 'Full-time', salary: '300-500$', location: 'قدسيا', posted: 'منذ يوم', image: 'https://images.unsplash.com/photo-1454165804606-c3d57bc86b40?auto=format&fit=crop&w=400&q=80', urgent: true },
  { id: 'j2', title: 'مطلوب موظف استقبال', titleEn: 'Receptionist Needed', company: 'فندق النخيل', companyEn: 'Palm Hotel', type: 'دوام كامل', typeEn: 'Full-time', salary: '250-350$', location: 'قدسيا', posted: 'منذ يومين', image: 'https://images.unsplash.com/photo-1573497019940-1c28c88b4f3e?auto=format&fit=crop&w=400&q=80' },
  { id: 'j3', title: 'مطلوب مبرمج', titleEn: 'Developer Needed', company: 'شركة التقنية', companyEn: 'Tech Co.', type: 'عن بعد', typeEn: 'Remote', salary: '500-800$', location: 'عن بعد', posted: 'منذ 3 أيام', image: 'https://images.unsplash.com/photo-1498050108023-c5249f4df085?auto=format&fit=crop&w=400&q=80', featured: true },
  { id: 'j4', title: 'مطلوب سائق', titleEn: 'Driver Needed', company: 'شركة النقل', companyEn: 'Transport Co.', type: 'دوام جزئي', typeEn: 'Part-time', salary: '200-300$', location: 'الضاحية', posted: 'منذ أسبوع', image: 'https://images.unsplash.com/photo-1449965408869-eaa3f722e40d?auto=format&fit=crop&w=400&q=80' },
  { id: 'j5', title: 'مطلوب ممرض/ة', titleEn: 'Nurse Needed', company: 'مستشفى الشفاء', companyEn: 'Al-Shifa Hospital', type: 'دوام كامل', typeEn: 'Full-time', salary: '400-600$', location: 'قدسيا', posted: 'منذ ساعتين', image: 'https://images.unsplash.com/photo-1559839734-2b71ea197ec2?auto=format&fit=crop&w=400&q=80', urgent: true },
  { id: 'j6', title: 'مطلوب معلم لغة', titleEn: 'Language Teacher Needed', company: 'معهد اللغات', companyEn: 'Language Institute', type: 'دوام جزئي', typeEn: 'Part-time', salary: '15$/ساعة', location: 'الساحة', posted: 'منذ 5 أيام', image: 'https://images.unsplash.com/photo-1573496359142-b8d87734a5a2?auto=format&fit=crop&w=400&q=80' },
];

// العقارات
const properties = [
  { id: 'p1', title: 'شقة فاخرة 180م²', titleEn: 'Luxury Apartment 180m²', type: 'للبيع', typeEn: 'For Sale', category: 'apartment', categoryAr: 'شقة', price: '280,000$', location: 'الحي الغربي', beds: 4, baths: 2, area: 180, image: 'https://images.unsplash.com/photo-1600596542815-ffad4c1539a9?auto=format&fit=crop&w=400&q=80', rating: 4.9, reviews: 23, featured: true },
  { id: 'p2', title: 'شقة للإيجار 120م²', titleEn: 'Apartment for Rent 120m²', type: 'للإيجار', typeEn: 'For Rent', category: 'apartment', categoryAr: 'شقة', price: '450$/شهر', location: 'المركز', beds: 3, baths: 2, area: 120, image: 'https://images.unsplash.com/photo-1600607687939-ce8a6c25118c?auto=format&fit=crop&w=400&q=80', rating: 4.7, reviews: 15, new: true },
  { id: 'p3', title: 'فيلا فاخرة مع حديقة', titleEn: 'Luxury Villa with Garden', type: 'للبيع', typeEn: 'For Sale', category: 'villa', categoryAr: 'فيلا', price: '950,000$', location: 'الحي الجنوبي', beds: 6, baths: 4, area: 380, image: 'https://images.unsplash.com/photo-1600585154340-be6161a56a0c?auto=format&fit=crop&w=400&q=80', rating: 5.0, reviews: 42, featured: true },
  { id: 'p4', title: 'مكتب تجاري 80م²', titleEn: 'Commercial Office 80m²', type: 'للإيجار', typeEn: 'For Rent', category: 'office', categoryAr: 'مكتب', price: '700$/شهر', location: 'الساحة', beds: 0, baths: 1, area: 80, image: 'https://images.unsplash.com/photo-1497366216548-37526070297c?auto=format&fit=crop&w=400&q=80', rating: 4.5, reviews: 8 },
  { id: 'p5', title: 'محل تجاري 45م²', titleEn: 'Commercial Shop 45m²', type: 'للإيجار', typeEn: 'For Rent', category: 'shop', categoryAr: 'محل', price: '550$/شهر', location: 'الشارع الرئيسي', beds: 0, baths: 1, area: 45, image: 'https://images.unsplash.com/photo-1604014237800-1c9102c219da?auto=format&fit=crop&w=400&q=80', rating: 4.3, reviews: 6 },
  { id: 'p6', title: 'أرض سكنية 500م²', titleEn: 'Residential Land 500m²', type: 'للبيع', typeEn: 'For Sale', category: 'land', categoryAr: 'أرض', price: '120,000$', location: 'المنطقة الخضراء', beds: 0, baths: 0, area: 500, image: 'https://images.unsplash.com/photo-1500382017468-9049fed747ef?auto=format&fit=crop&w=400&q=80', rating: 4.8, reviews: 5 },
  { id: 'p7', title: 'شقة غرفة وصالة', titleEn: 'One Bedroom Apartment', type: 'للإيجار', typeEn: 'For Rent', category: 'apartment', categoryAr: 'شقة', price: '280$/شهر', location: 'الحي الشرقي', beds: 1, baths: 1, area: 65, image: 'https://images.unsplash.com/photo-1600566753190-17f0baa2a6c3?auto=format&fit=crop&w=400&q=80', rating: 4.6, reviews: 12, new: true },
  { id: 'p8', title: 'دوبلكس فاخر 240م²', titleEn: 'Luxury Duplex 240m²', type: 'للبيع', typeEn: 'For Sale', category: 'apartment', categoryAr: 'شقة', price: '420,000$', location: 'برج الورود', beds: 5, baths: 3, area: 240, image: 'https://images.unsplash.com/photo-1600210492486-724fe5c67fb0?auto=format&fit=crop&w=400&q=80', rating: 4.9, reviews: 18, featured: true },
];

// المستعمل
const usedItems = [
  { id: 'u1', title: 'ثلاجة سامسونج نوفروست', titleEn: 'Samsung NoFrost Fridge', price: '380$', originalPrice: '650$', condition: 'جيدة جداً', conditionEn: 'Very Good', category: 'appliances', categoryAr: 'أجهزة', image: 'https://images.unsplash.com/photo-1571175443880-49e1d25b2bc5?auto=format&fit=crop&w=400&q=80', location: 'الحي الغربي', time: 'منذ يوم', featured: true },
  { id: 'u2', title: 'طقم كنب مودرن 7 قطع', titleEn: 'Modern Sofa Set 7 Pieces', price: '850$', originalPrice: '1,600$', condition: 'ممتاز', conditionEn: 'Excellent', category: 'furniture', categoryAr: 'أثاث', image: 'https://images.unsplash.com/photo-1555041469-a586c61ea9bc?auto=format&fit=crop&w=400&q=80', location: 'المركز', time: 'منذ 3 أيام', featured: true },
  { id: 'u3', title: 'لابتوب HP برو بوك i7', titleEn: 'HP ProBook i7 Laptop', price: '420$', originalPrice: '850$', condition: 'جيد', conditionEn: 'Good', category: 'electronics', categoryAr: 'إلكترونيات', image: 'https://images.unsplash.com/photo-1496181133206-80ce9b88a853?auto=format&fit=crop&w=400&q=80', location: 'قدسيا', time: 'منذ ساعتين', urgent: true },
  { id: 'u4', title: 'آيفون 14 برو ماكس 256GB', titleEn: 'iPhone 14 Pro Max 256GB', price: '850$', originalPrice: '1,200$', condition: 'ممتاز', conditionEn: 'Excellent', category: 'electronics', categoryAr: 'إلكترونيات', image: 'https://images.unsplash.com/photo-1678685888221-cda773a3dcdb?auto=format&fit=crop&w=400&q=80', location: 'الضاحية', time: 'منذ 5 ساعات', featured: true },
  { id: 'u5', title: 'دراجة هوائية جبلية', titleEn: 'Mountain Bike', price: '160$', originalPrice: '320$', condition: 'جيدة', conditionEn: 'Good', category: 'sports', categoryAr: 'رياضة', image: 'https://images.unsplash.com/photo-1532298229144-0ec0c57515c7?auto=format&fit=crop&w=400&q=80', location: 'قدسيا', time: 'منذ أسبوع' },
  { id: 'u6', title: 'غسالة LG أوتوماتيك', titleEn: 'LG Automatic Washing Machine', price: '280$', originalPrice: '500$', condition: 'جيدة جداً', conditionEn: 'Very Good', category: 'appliances', categoryAr: 'أجهزة', image: 'https://images.unsplash.com/photo-1626806118233-18e1de247200?auto=format&fit=crop&w=400&q=80', location: 'الشارع الرئيسي', time: 'منذ 4 أيام' },
  { id: 'u7', title: 'تلفزيون سامسونج 55 بوصة', titleEn: 'Samsung 55 inch TV', price: '320$', originalPrice: '600$', condition: 'جيدة جداً', conditionEn: 'Very Good', category: 'electronics', categoryAr: 'إلكترونيات', image: 'https://images.unsplash.com/photo-1593359677879-a4bb92f829d1?auto=format&fit=crop&w=400&q=80', location: 'قدسيا', time: 'منذ 6 ساعات', featured: true },
  { id: 'u8', title: 'سرير خشب زان مع مرتبة', titleEn: 'Oak Wood Bed with Mattress', price: '350$', originalPrice: '700$', condition: 'جيد', conditionEn: 'Good', category: 'furniture', categoryAr: 'أثاث', image: 'https://images.unsplash.com/photo-1505693416388-ac5ce068fe85?auto=format&fit=crop&w=400&q=80', location: 'الحي الشرقي', time: 'منذ يومين' },
];

// الحرفيين
const craftsmen = [
  { id: 'c1', name: 'أحمد الحداد', nameEn: 'Ahmed The Blacksmith', specialty: 'حدادة', specialtyEn: 'Blacksmith', rating: 4.8, reviews: 56, image: 'https://images.unsplash.com/photo-1504328345606-18bbc8c9d7d1?auto=format&fit=crop&w=400&q=80', location: 'الحي الصناعي', price: 'حسب العمل', experience: '15 سنة' },
  { id: 'c2', name: 'محمود النجار', nameEn: 'Mahmoud The Carpenter', specialty: 'نجارة', specialtyEn: 'Carpentry', rating: 4.9, reviews: 78, image: 'https://images.unsplash.com/photo-1567789884554-0b844b597180?auto=format&fit=crop&w=400&q=80', location: 'الشارع الرئيسي', price: 'حسب العمل', experience: '20 سنة', featured: true },
  { id: 'c3', name: 'يوسف السباك', nameEn: 'Youssef The Plumber', specialty: 'سباكة', specialtyEn: 'Plumbing', rating: 4.7, reviews: 45, image: 'https://images.unsplash.com/photo-1585704032915-c3400ca199e7?auto=format&fit=crop&w=400&q=80', location: 'قدسيا', price: 'حسب العمل', experience: '10 سنوات' },
  { id: 'c4', name: 'خالد الكهربائي', nameEn: 'Khaled The Electrician', specialty: 'كهرباء', specialtyEn: 'Electricity', rating: 4.8, reviews: 67, image: 'https://images.unsplash.com/photo-1621905252507-b35492cc74b4?auto=format&fit=crop&w=400&q=80', location: 'الضاحية', price: 'حسب العمل', experience: '12 سنة', featured: true },
  { id: 'c5', name: 'عمر الدهان', nameEn: 'Omar The Painter', specialty: 'دهان', specialtyEn: 'Painting', rating: 4.6, reviews: 34, image: 'https://images.unsplash.com/photo-1562259949-e8e7689d7828?auto=format&fit=crop&w=400&q=80', location: 'قدسيا', price: 'حسب المساحة', experience: '8 سنوات' },
  { id: 'c6', name: 'سمير البناء', nameEn: 'Samir The Builder', specialty: 'بناء', specialtyEn: 'Building', rating: 4.9, reviews: 89, image: 'https://images.unsplash.com/photo-1504307651254-35680f356dfd?auto=format&fit=crop&w=400&q=80', location: 'الحي الجنوبي', price: 'حسب العمل', experience: '25 سنة', featured: true },
];

// المهن الحرة
const professionals = [
  { id: 'pr1', name: 'د. أحمد المحامي', nameEn: 'Dr. Ahmed Lawyer', specialty: 'محامي', specialtyEn: 'Lawyer', rating: 4.9, reviews: 123, image: 'https://images.unsplash.com/photo-1589829545856-d10d557cf95f?auto=format&fit=crop&w=400&q=80', location: 'الساحة', price: 'استشارة: 10,000' },
  { id: 'pr2', name: 'م. سارة المهندسة', nameEn: 'Eng. Sara', specialty: 'مهندسة مدنية', specialtyEn: 'Civil Engineer', rating: 4.8, reviews: 67, image: 'https://images.unsplash.com/photo-1573496359142-b8d87734a5a2?auto=format&fit=crop&w=400&q=80', location: 'قدسيا', price: 'تصميم: 50,000', featured: true },
  { id: 'pr3', name: 'أ. محمد المحاسب', nameEn: 'Mohamed Accountant', specialty: 'محاسب قانوني', specialtyEn: 'CPA', rating: 4.7, reviews: 45, image: 'https://images.unsplash.com/photo-1560250097-0b93528c311a?auto=format&fit=crop&w=400&q=80', location: 'الشارع الرئيسي', price: 'استشارة: 5,000' },
  { id: 'pr4', name: 'م. فاطمة المعمارية', nameEn: 'Eng. Fatima Architect', specialty: 'معمارية', specialtyEn: 'Architect', rating: 4.9, reviews: 89, image: 'https://images.unsplash.com/photo-1580489944761-15a19d654956?auto=format&fit=crop&w=400&q=80', location: 'الحي الغربي', price: 'تصميم: 80,000', featured: true },
  { id: 'pr5', name: 'أ. نور المستشارة', nameEn: 'Nour Consultant', specialty: 'استشارية أعمال', specialtyEn: 'Business Consultant', rating: 4.6, reviews: 34, image: 'https://images.unsplash.com/photo-1544005313-94ddf0286df2?auto=format&fit=crop&w=400&q=80', location: 'قدسيا', price: 'استشارة: 15,000' },
  { id: 'pr6', name: 'م. خالد المصمم', nameEn: 'Eng. Khaled Designer', specialty: 'مصمم جرافيك', specialtyEn: 'Graphic Designer', rating: 4.8, reviews: 56, image: 'https://images.unsplash.com/photo-1472099645785-5658abf4ff4e?auto=format&fit=crop&w=400&q=80', location: 'عن بعد', price: 'تصميم: 20,000' },
];

// المكاتب والوسطاء
const offices = [
  { id: 'o1', name: 'مكتب الأمانة العقاري', nameEn: 'Al-Amana Real Estate', type: 'عقاري', typeEn: 'Real Estate', rating: 4.8, reviews: 234, image: 'https://images.unsplash.com/photo-1560518883-ce09059eeffa?auto=format&fit=crop&w=400&q=80', location: 'الساحة', listings: 45 },
  { id: 'o2', name: 'وكالة السفر والسياحة', nameEn: 'Travel & Tourism Agency', type: 'سفر', typeEn: 'Travel', rating: 4.6, reviews: 89, image: 'https://images.unsplash.com/photo-1488646953014-85cb44e25828?auto=format&fit=crop&w=400&q=80', location: 'الشارع الرئيسي', listings: 0 },
  { id: 'o3', name: 'مكتب الوسيط للسيارات', nameEn: 'Al-Waseet Cars', type: 'سيارات', typeEn: 'Cars', rating: 4.7, reviews: 156, image: 'https://images.unsplash.com/photo-1552519507-da3b142c6e3d?auto=format&fit=crop&w=400&q=80', location: 'مدخل الضاحية', listings: 28, featured: true },
  { id: 'o4', name: 'مكتب الترجمة المعتمد', nameEn: 'Certified Translation', type: 'ترجمة', typeEn: 'Translation', rating: 4.9, reviews: 67, image: 'https://images.unsplash.com/photo-1450101499163-c8848c66ca85?auto=format&fit=crop&w=400&q=80', location: 'الساحة', listings: 0 },
  { id: 'o5', name: 'وكالة التأمين الشامل', nameEn: 'Comprehensive Insurance', type: 'تأمين', typeEn: 'Insurance', rating: 4.5, reviews: 45, image: 'https://images.unsplash.com/photo-1450101499163-c8848c66ca85?auto=format&fit=crop&w=400&q=80', location: 'الشارع الرئيسي', listings: 0 },
];

// الخدمات المالية
const financialServices = [
  { id: 'f1', name: 'صراف الضاحية', nameEn: 'Dahia Exchange', type: 'صرافة', typeEn: 'Exchange', rating: 4.8, reviews: 189, image: 'https://images.unsplash.com/photo-1601597111158-2fceff292cdc?auto=format&fit=crop&w=400&q=80', location: 'الشارع الرئيسي', currencies: ['USD', 'EUR', 'SAR'] },
  { id: 'f2', name: 'شركة الحوالات', nameEn: 'Transfer Company', type: 'حوالات', typeEn: 'Transfers', rating: 4.7, reviews: 156, image: 'https://images.unsplash.com/photo-1556742049-0cfed4f6a45d?auto=format&fit=crop&w=400&q=80', location: 'الساحة', currencies: ['All'] },
  { id: 'f3', name: 'بنك التعاون', nameEn: 'Cooperation Bank', type: 'بنك', typeEn: 'Bank', rating: 4.6, reviews: 234, image: 'https://images.unsplash.com/photo-1543269865-cbf427effbad?auto=format&fit=crop&w=400&q=80', location: 'المركز', currencies: ['All'], featured: true },
  { id: 'f4', name: 'صراف النور', nameEn: 'Al-Noor Exchange', type: 'صرافة', typeEn: 'Exchange', rating: 4.5, reviews: 98, image: 'https://images.unsplash.com/photo-1601597111158-2fceff292cdc?auto=format&fit=crop&w=400&q=80', location: 'الحي الغربي', currencies: ['USD', 'EUR', 'AED'] },
];

// ═══════════════════════════════════════════════════════════════
// المكونات الفرعية
// ═══════════════════════════════════════════════════════════════

interface ProductCardProps {
  item: {
    id: string;
    title: string;
    titleEn: string;
    image: string;
    location?: string;
    price?: string;
    originalPrice?: string;
    rating?: number;
    reviews?: number;
    featured?: boolean;
    new?: boolean;
    urgent?: boolean;
    [key: string]: unknown;
  };
  isArabic: boolean;
  type: 'product' | 'job' | 'property' | 'service';
}

function ProductCard({ item, isArabic, type }: ProductCardProps) {
  const [isFavorite, setIsFavorite] = useState(false);

  const getDiscount = () => {
    if (!item.originalPrice || !item.price) return null;
    const p = parseInt(item.price.replace(/[^0-9]/g, ''));
    const o = parseInt(item.originalPrice.replace(/[^0-9]/g, ''));
    if (isNaN(p) || isNaN(o)) return null;
    return Math.round(((o - p) / o) * 100);
  };

  const discount = getDiscount();

  return (
    <div className="flex-shrink-0 w-[200px] sm:w-[240px] bg-white rounded-xl border border-gray-100 overflow-hidden hover:shadow-lg transition-all cursor-pointer group">
      {/* Image */}
      <div className="relative aspect-square overflow-hidden">
        <img src={item.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />

        {/* Favorite */}
        <button
          onClick={(e) => { e.stopPropagation(); setIsFavorite(!isFavorite); }}
          className="absolute top-2 right-2 p-1.5 bg-white/80 rounded-full hover:bg-white transition-colors"
        >
          <Heart className={`w-4 h-4 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-gray-600'}`} />
        </button>

        {/* Badges */}
        <div className="absolute top-2 left-2 flex flex-col gap-1">
          {discount && (
            <span className="px-2 py-0.5 bg-rose-500 text-white text-[10px] font-bold rounded-full">
              -{discount}%
            </span>
          )}
          {item.urgent && (
            <span className="px-2 py-0.5 bg-red-500 text-white text-[10px] font-bold rounded-full animate-pulse">
              {isArabic ? 'عاجل' : 'Urgent'}
            </span>
          )}
          {item.featured && !item.urgent && (
            <span className="px-2 py-0.5 bg-amber-500 text-white text-[10px] font-bold rounded-full">
              {isArabic ? 'مميز' : 'Featured'}
            </span>
          )}
        </div>
      </div>

      {/* Info */}
      <div className="p-3">
        <h3 className="font-bold text-gray-900 text-sm mb-1 line-clamp-2">
          {isArabic ? item.title : item.titleEn}
        </h3>

        {item.rating && (
          <div className="flex items-center gap-1 mb-1">
            <Star className="w-3.5 h-3.5 text-amber-400 fill-amber-400" />
            <span className="text-xs font-bold">{item.rating}</span>
            <span className="text-xs text-gray-400">({item.reviews})</span>
          </div>
        )}

        {item.price && (
          <div className="flex items-center gap-2 mb-1">
            <span className="text-sm font-black text-gray-900">{item.price}</span>
            {item.originalPrice && (
              <span className="text-xs text-gray-400 line-through">{item.originalPrice}</span>
            )}
          </div>
        )}

        {item.location && (
          <div className="flex items-center gap-1 text-xs text-gray-500">
            <MapPin className="w-3 h-3" />
            <span className="line-clamp-1">{item.location}</span>
          </div>
        )}
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
  items: ProductCardProps['item'][];
  isArabic: boolean;
  type: ProductCardProps['type'];
  filters?: { id: string; name: string; nameEn: string }[];
  getFilterValue?: (item: ProductCardProps['item'], filterId: string) => boolean;
}

function Section({ id, title, titleEn, icon: Icon, iconColor, items, isArabic, type, filters, getFilterValue }: SectionProps) {
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
            <ProductCard key={item.id} item={item} isArabic={isArabic} type={type} />
          ))}
        </div>
      </div>
    </section>
  );
}

// ═══════════════════════════════════════════════════════════════
// الصفحة الرئيسية
// ═══════════════════════════════════════════════════════════════

export default function MarketPage() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const [searchQuery, setSearchQuery] = useState('');
  const [backendListings, setBackendListings] = useState<Listing[]>([]);
  const [backendLoading, setBackendLoading] = useState(true);
  const [backendError, setBackendError] = useState<string | null>(null);

  React.useEffect(() => {
    let active = true;

    const loadBackendListings = async () => {
      try {
        setBackendLoading(true);
        setBackendError(null);
        const response = await catalogService.list({ page: 0, size: 8 });
        if (!active) return;
        setBackendListings(response.content ?? []);
      } catch (error) {
        if (!active) return;
        setBackendError(
          isArabic
            ? 'تعذر جلب بيانات الباك إند. قد تحتاج تسجيل دخول JWT.'
            : 'Unable to load backend listings. JWT login may be required.',
        );
      } finally {
        if (active) setBackendLoading(false);
      }
    };

    loadBackendListings();

    return () => {
      active = false;
    };
  }, [isArabic]);

  const sections = [
    { id: 'jobs', name: 'وظائف وفرص', nameEn: 'Jobs & Opportunities' },
    { id: 'properties', name: 'عقارات وإيجارات', nameEn: 'Real Estate' },
    { id: 'used', name: 'مستعمل وإعلانات', nameEn: 'Used & Classifieds' },
    { id: 'craftsmen', name: 'حرفيين ومهنيين', nameEn: 'Craftsmen' },
    { id: 'professionals', name: 'مهن حرة', nameEn: 'Professionals' },
    { id: 'offices', name: 'مكاتب ووسطاء', nameEn: 'Offices & Agents' },
    { id: 'financial', name: 'خدمات مالية', nameEn: 'Financial Services' },
  ];

  const scrollToSection = (id: string) => {
    document.getElementById(id)?.scrollIntoView({ behavior: 'smooth' });
  };

  return (
    <main className="min-h-screen bg-gradient-to-b from-teal-50 to-white" dir={isArabic ? 'rtl' : 'ltr'}>
      {/* Header */}
      <header className="sticky top-0 z-50 bg-white/95 backdrop-blur-md border-b border-teal-100 shadow-sm">
        <div className="max-w-7xl mx-auto px-4 py-3">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <Link href="/" className="p-2 hover:bg-gray-100 rounded-xl transition-colors">
                <ArrowRight className="w-5 h-5 text-gray-600" />
              </Link>
              <div className="p-2 bg-gradient-to-br from-teal-500 to-emerald-600 rounded-xl shadow-lg">
                <ShoppingBag className="w-6 h-6 text-white" />
              </div>
              <div>
                <h1 className="text-lg font-black text-gray-900">
                  {isArabic ? 'السوق والإعلانات' : 'Market & Classifieds'}
                </h1>
                <p className="text-xs text-gray-500">
                  {isArabic ? 'عقارات، مستعمل، وظائف وإعلانات' : 'Real estate, used items, jobs & ads'}
                </p>
              </div>
            </div>
            <button className="p-2 bg-teal-100 text-teal-600 rounded-xl hover:bg-teal-200 transition-colors">
              <Filter className="w-5 h-5" />
            </button>
          </div>

          {/* Search */}
          <div className="mt-3 relative">
            <Search className="absolute right-3 top-1/2 -translate-y-1/2 w-5 h-5 text-gray-400" />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder={isArabic ? 'ابحث عن منتج، عقار، وظيفة...' : 'Search for product, property, job...'}
              className="w-full pr-10 pl-4 py-3 bg-gray-100 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-teal-500"
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
                className="px-4 py-2 bg-gray-100 hover:bg-teal-100 text-gray-700 hover:text-teal-700 text-xs font-bold rounded-xl whitespace-nowrap transition-colors"
              >
                {isArabic ? section.name : section.nameEn}
              </button>
            ))}
          </div>
        </div>
      </div>

      <div className="max-w-7xl mx-auto px-4">
        {/* Backend Integration Preview */}
        <section className="scroll-mt-32 py-6 border-b border-gray-100">
          <div className="flex items-center gap-3 mb-4">
            <div className="p-2 bg-indigo-500 rounded-xl">
              <Store className="w-5 h-5 text-white" />
            </div>
            <div className="flex-1">
              <h2 className="text-lg font-black text-gray-900">
                {isArabic ? '📡 من الباك إند (Listings API)' : '📡 From Backend (Listings API)'}
              </h2>
              <p className="text-xs text-gray-500">
                {isArabic ? 'مصدر البيانات: /api/v1/listings' : 'Data source: /api/v1/listings'}
              </p>
            </div>
          </div>

          {backendLoading && (
            <div className="bg-indigo-50 border border-indigo-100 rounded-xl p-4 text-sm text-indigo-700">
              {isArabic ? 'جاري تحميل بيانات الباك إند...' : 'Loading backend data...'}
            </div>
          )}

          {!backendLoading && backendError && (
            <div className="bg-amber-50 border border-amber-100 rounded-xl p-4 text-sm text-amber-700">
              {backendError}
            </div>
          )}

          {!backendLoading && !backendError && backendListings.length === 0 && (
            <div className="bg-gray-50 border border-gray-200 rounded-xl p-4 text-sm text-gray-600">
              {isArabic ? 'لا توجد عناصر قادمة من الباك إند حالياً.' : 'No backend listings available yet.'}
            </div>
          )}

          {!backendLoading && !backendError && backendListings.length > 0 && (
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-3">
              {backendListings.map((listing) => (
                <article key={listing.id} className="bg-white rounded-xl border border-gray-100 p-3 shadow-sm">
                  <h3 className="font-bold text-sm text-gray-900 line-clamp-2">{listing.title}</h3>
                  <p className="text-xs text-gray-500 mt-1 line-clamp-2">{listing.description}</p>
                  <div className="mt-3 flex items-center justify-between text-xs">
                    <span className="px-2 py-1 rounded-full bg-gray-100 text-gray-700">{listing.category}</span>
                    <span className="font-black text-emerald-700">
                      {listing.price} {listing.currency}
                    </span>
                  </div>
                </article>
              ))}
            </div>
          )}
        </section>

        {/* الوظائف */}
        <Section
          id="jobs"
          title="💼 وظائف وفرص عمل"
          titleEn="💼 Jobs & Opportunities"
          icon={Briefcase}
          iconColor="bg-blue-500"
          items={jobs}
          isArabic={isArabic}
          type="job"
          filters={[
            { id: 'all', name: 'الكل', nameEn: 'All' },
            { id: 'full', name: 'دوام كامل', nameEn: 'Full-time' },
            { id: 'part', name: 'دوام جزئي', nameEn: 'Part-time' },
            { id: 'remote', name: 'عن بعد', nameEn: 'Remote' },
          ]}
          getFilterValue={(item, filterId) => {
            if (filterId === 'full') return item.type === 'دوام كامل';
            if (filterId === 'part') return item.type === 'دوام جزئي';
            if (filterId === 'remote') return item.type === 'عن بعد';
            return true;
          }}
        />

        {/* العقارات */}
        <Section
          id="properties"
          title="🏡 عقارات وإيجارات"
          titleEn="🏡 Real Estate"
          icon={Building}
          iconColor="bg-emerald-500"
          items={properties}
          isArabic={isArabic}
          type="property"
          filters={[
            { id: 'all', name: 'الكل', nameEn: 'All' },
            { id: 'sale', name: 'للبيع', nameEn: 'For Sale' },
            { id: 'rent', name: 'للإيجار', nameEn: 'For Rent' },
            { id: 'apartment', name: 'شقق', nameEn: 'Apartments' },
            { id: 'villa', name: 'فلل', nameEn: 'Villas' },
          ]}
          getFilterValue={(item, filterId) => {
            if (filterId === 'sale') return item.type === 'للبيع';
            if (filterId === 'rent') return item.type === 'للإيجار';
            if (filterId === 'apartment') return item.category === 'apartment';
            if (filterId === 'villa') return item.category === 'villa';
            return true;
          }}
        />

        {/* المستعمل */}
        <Section
          id="used"
          title="📦 مستعمل وإعلانات"
          titleEn="📦 Used & Classifieds"
          icon={Package}
          iconColor="bg-amber-500"
          items={usedItems}
          isArabic={isArabic}
          type="product"
          filters={[
            { id: 'all', name: 'الكل', nameEn: 'All' },
            { id: 'electronics', name: 'إلكترونيات', nameEn: 'Electronics' },
            { id: 'furniture', name: 'أثاث', nameEn: 'Furniture' },
            { id: 'appliances', name: 'أجهزة', nameEn: 'Appliances' },
            { id: 'sports', name: 'رياضة', nameEn: 'Sports' },
          ]}
          getFilterValue={(item, filterId) => {
            if (filterId === 'electronics') return item.category === 'electronics';
            if (filterId === 'furniture') return item.category === 'furniture';
            if (filterId === 'appliances') return item.category === 'appliances';
            if (filterId === 'sports') return item.category === 'sports';
            return true;
          }}
        />

        {/* الحرفيين */}
        <Section
          id="craftsmen"
          title="🔧 حرفيين ومهنيين"
          titleEn="🔧 Craftsmen"
          icon={Briefcase}
          iconColor="bg-gray-500"
          items={craftsmen}
          isArabic={isArabic}
          type="service"
          filters={[
            { id: 'all', name: 'الكل', nameEn: 'All' },
            { id: 'blacksmith', name: 'حدادة', nameEn: 'Blacksmith' },
            { id: 'carpenter', name: 'نجارة', nameEn: 'Carpentry' },
            { id: 'plumber', name: 'سباكة', nameEn: 'Plumbing' },
            { id: 'electrician', name: 'كهرباء', nameEn: 'Electricity' },
          ]}
          getFilterValue={(item, filterId) => {
            if (filterId === 'blacksmith') return item.specialty === 'حدادة';
            if (filterId === 'carpenter') return item.specialty === 'نجارة';
            if (filterId === 'plumber') return item.specialty === 'سباكة';
            if (filterId === 'electrician') return item.specialty === 'كهرباء';
            return true;
          }}
        />

        {/* المهن الحرة */}
        <Section
          id="professionals"
          title="👔 المهن الحرة المتخصصة"
          titleEn="👔 Professionals"
          icon={Scale}
          iconColor="bg-violet-500"
          items={professionals}
          isArabic={isArabic}
          type="service"
          filters={[
            { id: 'all', name: 'الكل', nameEn: 'All' },
            { id: 'lawyer', name: 'محامين', nameEn: 'Lawyers' },
            { id: 'engineer', name: 'مهندسين', nameEn: 'Engineers' },
            { id: 'accountant', name: 'محاسبين', nameEn: 'Accountants' },
            { id: 'consultant', name: 'استشاريين', nameEn: 'Consultants' },
          ]}
          getFilterValue={(item, filterId) => {
            if (filterId === 'lawyer') return item.specialty?.includes('محامي');
            if (filterId === 'engineer') return item.specialty?.includes('مهندس') || item.specialty?.includes('معمار');
            if (filterId === 'accountant') return item.specialty?.includes('محاسب');
            if (filterId === 'consultant') return item.specialty?.includes('استشار');
            return true;
          }}
        />

        {/* المكاتب */}
        <Section
          id="offices"
          title="🏢 مكاتب ووسطاء"
          titleEn="🏢 Offices & Agents"
          icon={Building2}
          iconColor="bg-slate-500"
          items={offices}
          isArabic={isArabic}
          type="service"
          filters={[
            { id: 'all', name: 'الكل', nameEn: 'All' },
            { id: 'realestate', name: 'عقارية', nameEn: 'Real Estate' },
            { id: 'travel', name: 'سفر', nameEn: 'Travel' },
            { id: 'cars', name: 'سيارات', nameEn: 'Cars' },
            { id: 'translation', name: 'ترجمة', nameEn: 'Translation' },
          ]}
          getFilterValue={(item, filterId) => {
            if (filterId === 'realestate') return item.type === 'عقاري';
            if (filterId === 'travel') return item.type === 'سفر';
            if (filterId === 'cars') return item.type === 'سيارات';
            if (filterId === 'translation') return item.type === 'ترجمة';
            return true;
          }}
        />

        {/* الخدمات المالية */}
        <Section
          id="financial"
          title="💰 خدمات مالية"
          titleEn="💰 Financial Services"
          icon={Banknote}
          iconColor="bg-emerald-500"
          items={financialServices}
          isArabic={isArabic}
          type="service"
          filters={[
            { id: 'all', name: 'الكل', nameEn: 'All' },
            { id: 'exchange', name: 'صرافين', nameEn: 'Exchange' },
            { id: 'transfer', name: 'حوالات', nameEn: 'Transfers' },
            { id: 'bank', name: 'بنوك', nameEn: 'Banks' },
          ]}
          getFilterValue={(item, filterId) => {
            if (filterId === 'exchange') return item.type === 'صرافة';
            if (filterId === 'transfer') return item.type === 'حوالات';
            if (filterId === 'bank') return item.type === 'بنك';
            return true;
          }}
        />
      </div>

      {/* Footer CTA */}
      <div className="sticky bottom-0 bg-gradient-to-r from-teal-600 to-emerald-600 p-4 shadow-lg">
        <div className="max-w-7xl mx-auto flex items-center justify-between">
          <span className="text-white font-bold">
            {isArabic ? 'هل لديك شيء للبيع؟' : 'Have something to sell?'}
          </span>
          <button className="flex items-center gap-2 px-6 py-3 bg-white text-teal-600 rounded-xl font-bold hover:bg-gray-100 transition-colors">
            <Tag className="w-5 h-5" />
            {isArabic ? 'أضف إعلان' : 'Post Ad'}
          </button>
        </div>
      </div>
    </main>
  );
}
